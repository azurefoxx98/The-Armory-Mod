package me.ladypaladra.thearmorymod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchlyStartupTest {

    @TempDir
    Path directory;

    private Path stale(String group, String name) throws IOException {
        Path output = Files.createDirectories(directory.resolve("LadyPaladra_TheArmory_Patchly"));
        Files.writeString(output.resolve("manifest.json"),
                "{\"Group\":\"" + group + "\",\"Name\":\"" + name + "\",\"IncludesAssetPack\":true}");
        Path server = Files.createDirectories(output.resolve("Server/Item/Items"));
        Files.writeString(server.resolve("Armor_Iron_Head.json"), "{}");
        return output;
    }

    @Test
    void onlyGeneratedOutputIsRemovedAndASecondRunDoesNothing() throws IOException {
        Path unrelated = Files.writeString(directory.resolve("another-plugin.jar"), "keep");
        Path output = stale("LadyPaladra", "TheArmory_Patchly");
        assertTrue(PatchlyStartup.cleanGeneratedPack(directory, "LadyPaladra", "TheArmory"));
        assertFalse(Files.exists(output));
        assertTrue(Files.exists(unrelated));
        assertFalse(PatchlyStartup.cleanGeneratedPack(directory, "LadyPaladra", "TheArmory"));
    }

    @Test
    void anotherOwnersManifestIsRefusedWithoutDeletingAnything() throws IOException {
        Path output = stale("SomeoneElse", "TheArmory_Patchly");
        assertThrows(IOException.class, () -> PatchlyStartup.cleanGeneratedPack(directory, "LadyPaladra", "TheArmory"));
        assertTrue(Files.exists(output.resolve("Server/Item/Items/Armor_Iron_Head.json")));
    }

    @Test
    void aMissingManifestIsRefusedWithoutDeletingAnything() throws IOException {
        Path output = stale("LadyPaladra", "TheArmory_Patchly");
        Files.delete(output.resolve("manifest.json"));
        assertThrows(IOException.class, () -> PatchlyStartup.cleanGeneratedPack(directory, "LadyPaladra", "TheArmory"));
        assertTrue(Files.exists(output.resolve("Server/Item/Items/Armor_Iron_Head.json")));
    }

    @Test
    void pathTraversalCannotChooseAnotherDirectory() {
        assertThrows(IOException.class, () -> PatchlyStartup.cleanGeneratedPack(directory, "../outside", "TheArmory"));
    }
}
