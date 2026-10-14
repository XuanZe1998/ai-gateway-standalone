package org.unreal.modelrouter.catalog;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelIconLibraryTest {
    @Test void realModelIdWinsOverRoutingVendorAndDisplayName() {
        assertEquals("deepseek", ModelIconLibrary.resolve("chat", "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B", "Claude", List.of("OpenAI")));
        assertEquals("qwen", ModelIconLibrary.resolve("embedding", "text-embedding-v4", "Embedding", List.of()));
        assertEquals("openai", ModelIconLibrary.resolve("embedding", "text-embedding-3-large", "Embedding", List.of()));
        assertEquals("wan", ModelIconLibrary.resolve("vidGen", "wan3.0-video", "Video", List.of()));
        assertEquals("happyhorse", ModelIconLibrary.resolve("vidGen", "happyhorse-1.0-t2v", "Video", List.of()));
    }

    @Test void opaqueIdsUseMetadataOrAnHonestServiceFallback() {
        assertEquals("claude", ModelIconLibrary.resolve("chat", "internal-id", "Claude Sonnet", List.of()));
        assertEquals("qwen", ModelIconLibrary.resolve("chat", "internal-id", "模型", List.of("通义千问")));
        assertEquals("generic-chat", ModelIconLibrary.resolve("chat", "orb_base_60_std_zj", "orb_base_60_std_zj", List.of()));
        assertEquals("generic-chat", ModelIconLibrary.resolve("chat", "orb_base_60_std_zj", "Claude", List.of("OpenAI")));
        assertEquals("generic-stt", ModelIconLibrary.resolve("stt", "local-speech", "本地语音", List.of()));
        assertEquals("generic-unknown", ModelIconLibrary.resolve("other", "local", "本地", List.of()));
    }

    @Test void overridesOnlyAcceptKnownLocalAssetIds() {
        assertTrue(ModelIconLibrary.contains("deepseek"));
        assertTrue(ModelIconLibrary.contains("generic-chat"));
        assertFalse(ModelIconLibrary.contains("../../secret"));
        assertFalse(ModelIconLibrary.contains("https://example.com/icon.svg"));
        assertFalse(ModelIconLibrary.contains(null));
    }
}
