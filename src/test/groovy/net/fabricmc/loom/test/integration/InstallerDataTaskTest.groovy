/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.test.integration

import java.nio.file.Files
import java.nio.file.StandardCopyOption

import spock.lang.Specification

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.test.util.ZipTestUtils

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class InstallerDataTaskTest extends Specification implements GradleProjectTestTrait {
	def "installer data is scanned and downloaded lazily"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap")
		def repository = new File(gradle.projectDir, "installer-repository")
		def repositoryJar = new File(repository, "example/installer-library/1.0/installer-library-1.0.jar")
		repositoryJar.parentFile.mkdirs()
		def libraryJar = ZipTestUtils.createZip(["example/Library.class": "library"], ".jar")
		Files.copy(libraryJar, repositoryJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
		def installerJson = """{
			"mainClass": {
				"client": "example.CustomClient",
				"server": "example.CustomServer"
			},
			"libraries": {
				"common": [
					{
						"name": "example:installer-library:1.0",
						"url": "${repository.toURI()}"
					}
				]
			}
		}"""
		Map<String, String> installerContents = [:]
		installerContents["fabric.mod.json"] = '{"id":"test-loader","version":"9.8.7"}'
		installerContents["fabric-installer.json"] = installerJson
		def carrier = ZipTestUtils.createZip(installerContents, ".jar")
		Files.copy(carrier, new File(gradle.projectDir, "installer-carrier.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
		gradle.buildGradle << '''
			repositories.clear()

			loom {
				splitEnvironmentSourceSets()

				runs {
					inheritedClient {
						inherit client
						displayName = "Inherited Client"
					}

					configureEach {
						preferGradleTask = false
					}
				}
			}

			dependencies {
				minecraft "com.mojang:minecraft:1.18.1"
				modImplementation files("installer-carrier.jar")
			}

			def productionServer = tasks.create("productionServerDefaults", net.fabricmc.loom.task.prod.ServerProductionRunTask)
			def manifestService = net.fabricmc.loom.task.service.JarManifestService.get(project)
			tasks.register("checkInstallerConsumers") {
				dependsOn "scanInstallerData"
				usesService(manifestService)
				doLast {
					def manifest = new java.util.jar.Manifest()
					manifestService.get().apply(manifest, [:])
					println("CLIENT_MAIN=" + loom.runs.named("client").get().mainClass.get())
					println("SERVER_MAIN=" + loom.runs.named("server").get().mainClass.get())
					println("PRODUCTION_LOADER=" + productionServer.loaderVersion.get())
					println("MANIFEST_LOADER=" + manifest.mainAttributes.getValue("Fabric-Loader-Version"))
				}
			}
		'''
		def descriptor = new File(gradle.projectDir, ".gradle/loom-cache/installer-data/descriptor.json")
		def selectedInstallerJar = new File(gradle.projectDir, ".gradle/loom-cache/installer-data/loader/installer.jar")
		def downloadedJar = new File(gradle.projectDir, ".gradle/loom-cache/installer-data/libraries/example/installer-library/1.0/installer-library-1.0.jar")

		when:
		def help = gradle.run(task: "help")

		then:
		help.task(":scanInstallerData") == null
		help.task(":downloadInstallerLibraries") == null
		!descriptor.exists()
		!selectedInstallerJar.exists()
		!downloadedJar.exists()

		when:
		def ideaSync = gradle.run(task: "ideaSyncTask")
		def clientRunConfig = new File(gradle.projectDir, ".idea/runConfigurations/Minecraft_Client.xml")
		def serverRunConfig = new File(gradle.projectDir, ".idea/runConfigurations/Minecraft_Server.xml")
		def inheritedRunConfig = new File(gradle.projectDir, ".idea/runConfigurations/Inherited_Client.xml")

		then:
		ideaSync.task(":scanInstallerData").outcome in [SUCCESS, FROM_CACHE]
		ideaSync.task(":ideaSyncTask").outcome == SUCCESS
		clientRunConfig.text.contains("-Dfabric.dli.main=example.CustomClient")
		serverRunConfig.text.contains("-Dfabric.dli.main=example.CustomServer")
		inheritedRunConfig.text.contains("-Dfabric.dli.main=example.CustomClient")

		when:
		def consumers = gradle.run(task: "checkInstallerConsumers", configurationCache: false)

		then:
		consumers.task(":scanInstallerData").outcome == UP_TO_DATE
		consumers.task(":checkInstallerConsumers").outcome == SUCCESS
		consumers.output.contains("CLIENT_MAIN=example.CustomClient")
		consumers.output.contains("SERVER_MAIN=example.CustomServer")
		consumers.output.contains("PRODUCTION_LOADER=9.8.7")
		consumers.output.contains("MANIFEST_LOADER=9.8.7")
		descriptor.isFile()
		selectedInstallerJar.isFile()
		Files.mismatch(new File(gradle.projectDir, "installer-carrier.jar").toPath(), selectedInstallerJar.toPath()) == -1
		!downloadedJar.exists()

		when:
		def first = gradle.run(task: "downloadInstallerLibraries")
		def second = gradle.run(task: "downloadInstallerLibraries")

		then:
		first.task(":scanInstallerData").outcome == UP_TO_DATE
		first.task(":downloadInstallerLibraries").outcome == SUCCESS
		downloadedJar.isFile()
		Files.mismatch(repositoryJar.toPath(), downloadedJar.toPath()) == -1
		second.task(":scanInstallerData").outcome == UP_TO_DATE
		second.task(":downloadInstallerLibraries").outcome == UP_TO_DATE
		second.output.contains("Reusing configuration cache.")

		cleanup:
		Files.deleteIfExists(libraryJar)
		Files.deleteIfExists(carrier)
	}
}
