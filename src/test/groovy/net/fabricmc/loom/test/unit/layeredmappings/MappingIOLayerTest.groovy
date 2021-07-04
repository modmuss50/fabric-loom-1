package net.fabricmc.loom.test.unit.layeredmappings

import net.fabricmc.loom.configuration.providers.mappings.PathSpec
import net.fabricmc.loom.configuration.providers.mappings.intermediary.IntermediaryMappingsSpec
import net.fabricmc.loom.configuration.providers.mappings.tiny.MappingIOSpec

class MappingIOLayerTest extends LayeredMappingsSpecification {
    def "Read yarn zip" () {
        setup:
            mockMappingsProvider.intermediaryTinyFile() >> extractFileFromZip(downloadFile(INTERMEDIARY_1_17_URL, "intermediary.jar"), "mappings/mappings.tiny")
            mockMinecraftProvider.getVersionInfo() >> VERSION_META_1_17
            File yarn = downloadFile(YARN_1_17_URL, "yarn.jar");
        when:
            def mappings = getLayeredMappings(
                    new IntermediaryMappingsSpec(),
                    new MappingIOSpec(PathSpec.create(yarn), "mappings/mappings.tiny")
            )
            def tiny = getTiny(mappings)
        then:
            mappings.srcNamespace == "named"
            mappings.dstNamespaces == ["intermediary", "official"]
    }

    def "Read yarn file" () {
        setup:
            mockMappingsProvider.intermediaryTinyFile() >> extractFileFromZip(downloadFile(INTERMEDIARY_1_17_URL, "intermediary.jar"), "mappings/mappings.tiny")
            mockMinecraftProvider.getVersionInfo() >> VERSION_META_1_17
            File yarn = extractFileFromZip(downloadFile(YARN_1_17_URL, "yarn.jar"), "mappings/mappings.tiny")
        when:
            def mappings = getLayeredMappings(
                    new IntermediaryMappingsSpec(),
                    new MappingIOSpec(PathSpec.create(yarn), null)
            )
            def tiny = getTiny(mappings)
        then:
            mappings.srcNamespace == "named"
            mappings.dstNamespaces == ["intermediary", "official"]
    }
}
