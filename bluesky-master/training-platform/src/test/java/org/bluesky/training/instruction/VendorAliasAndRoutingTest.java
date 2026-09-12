package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P10：厂商别名矩阵与意图路由（TDD 计划 15.3.7 / 详细设计 2.2 §7.10）。 */
class VendorAliasAndRoutingTest {

    @Test
    void givenAliasMatrixWhenNormalizedThenCanonicalForms() {
        assertEquals("ALT 30000FT", VendorAliasNormalizer.normalize("LVL 30000FT"));
        assertEquals("SPD 280KT", VendorAliasNormalizer.normalize("SPEED 280KT"));
        assertEquals("VS +1000", VendorAliasNormalizer.normalize("CR 1000"));
        assertEquals("VS -1000", VendorAliasNormalizer.normalize("DR 1000"));
        assertEquals("SQK 0042", VendorAliasNormalizer.normalize("SSRCODE 0042"));
        assertEquals("OFFSET CLR", VendorAliasNormalizer.normalize("OFFSET"),
                "空参数 OFFSET 等同 CLR");
        assertEquals("OFFSET R 5NM", VendorAliasNormalizer.normalize("OFFSET R5"));
        assertEquals("OFFSET L 3NM", VendorAliasNormalizer.normalize("OFFSET L3"));
        assertEquals("OFFSET R 4NM", VendorAliasNormalizer.normalize("OFFSET 4"),
                "裸数等同右偏置");
        assertEquals("FRE 121.500", VendorAliasNormalizer.normalize("FRE 121.500"));
    }

    @Test
    void givenIdWhenNormalizedThenNeverBecomesIdent() {
        assertEquals("ID", VendorAliasNormalizer.normalizeKeyword("ID"));
        assertEquals("IDENT", VendorAliasNormalizer.normalizeKeyword("IDENT"));
        // 规范化整句后 ID 仍是 ID
        assertEquals("ID", VendorAliasNormalizer.normalize("ID").split("\\s+")[0]);
    }

    @Test
    void givenUnknownCommandWhenNormalizedThenRejected() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> VendorAliasNormalizer.normalize("HOVER 100"));
        assertEquals("INVALID_INSTRUCTION", failure.code());
        assertThrows(V2DomainException.class, () -> VendorAliasNormalizer.normalize(""));
    }

    @Test
    void givenFreOrDelWhenRoutedThenNoInstructionIsCreated() {
        CommandIntentRouter router = new CommandIntentRouter();

        assertEquals("HANDOVER", router.route("FRE 121.500").get("intent"));
        assertEquals("DELETION_PREVIEW", router.route("DEL").get("intent"));
        assertEquals("INSTRUCTION", router.route("HDG 090").get("intent"));
        assertEquals("UNKNOWN", router.route("HOVER 100").get("intent"));

        // FRE/DEL 提交到指令接口必须被拦截
        V2DomainException fre = assertThrows(V2DomainException.class,
                () -> CommandIntentRouter.requireInstructionIntent("FRE 121.500"));
        assertEquals("INVALID_INSTRUCTION", fre.code());
        assertThrows(V2DomainException.class,
                () -> CommandIntentRouter.requireInstructionIntent("DEL"));
    }

    @Test
    void givenPcaPresetsWhenResolvedThenInstantRequiresExplicitConfig() {
        assertEquals("NORMAL", PcaRatePresets.resolveRatePreset("NORMAL", false));
        assertEquals("SLOW", PcaRatePresets.resolveRatePreset("SLOW", false));
        assertEquals("INSTANT", PcaRatePresets.resolveRatePreset("INSTANT", true));

        V2DomainException disabled = assertThrows(V2DomainException.class,
                () -> PcaRatePresets.resolveRatePreset("INSTANT", false));
        assertEquals("PERFORMANCE_LIMIT_EXCEEDED", disabled.code());
        assertThrows(V2DomainException.class,
                () -> PcaRatePresets.resolveRatePreset("TURBO", true));
    }

    @Test
    void givenCatalogWhenHelpGeneratedThenCoversAllCommands() {
        InstructionCatalog catalog = new InstructionCatalog();
        List<Map<String, Object>> help = PcaRatePresets.generateHelp(catalog);

        assertEquals(catalog.allDefinitions().size(), help.size());
        assertTrue(help.stream().anyMatch(entry -> "HDG".equals(entry.get("type"))
                && "LATERAL".equals(entry.get("channel"))));
        assertTrue(help.stream().anyMatch(entry -> "ALT".equals(entry.get("type"))
                && List.class.cast(entry.get("aliases")).contains("LVL")));
    }
}
