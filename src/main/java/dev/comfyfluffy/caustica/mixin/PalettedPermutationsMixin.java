package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.compat.LegacyTrimPalettes;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Resolves armor-trim palettes that an outdated resource pack names by their pre-26.x paths.
 *
 * <p>Only the palette lookup inside {@code loadPaletteEntryFromImage} is wrapped, and only a lookup that
 * vanilla could not satisfy is retried, so a pack using current names and vanilla itself behave exactly
 * as before. See {@link LegacyTrimPalettes} for the mapping.
 */
@Mixin(PalettedPermutations.class)
public abstract class PalettedPermutationsMixin {
    @Unique
    private static final AtomicBoolean caustica$announced = new AtomicBoolean();

    @WrapOperation(
            method = "loadPaletteEntryFromImage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResource(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;"))
    private static Optional<Resource> caustica$legacyTrimPalette(ResourceManager resourceManager, Identifier file,
                                                                 Operation<Optional<Resource>> original,
                                                                 ResourceManager unusedManager, Identifier paletteId) {
        Optional<Resource> resource = original.call(resourceManager, file);
        if (resource.isPresent()) {
            return resource;
        }
        for (String path : LegacyTrimPalettes.fallbackPaths(paletteId.getPath())) {
            Identifier candidate = Identifier.fromNamespaceAndPath(paletteId.getNamespace(), path);
            Optional<Resource> found = original.call(resourceManager, candidate);
            if (found.isPresent()) {
                if (caustica$announced.compareAndSet(false, true)) {
                    CausticaMod.LOGGER.info("A resource pack names armor-trim palettes by their pre-26.x paths "
                            + "(e.g. {}); resolving them from {} instead", paletteId, candidate);
                }
                return found;
            }
        }
        return resource;
    }
}
