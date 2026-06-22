package dev.hytalemodding.commands;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.ExamplePlugin;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class IniciarBackroomsCommand extends AbstractPlayerCommand {

    private final ExamplePlugin.MatchManager matchManager;

    public IniciarBackroomsCommand(ExamplePlugin.MatchManager matchManager) {
        super("iniciar_backrooms", "Entra al Nivel 0 de los Backrooms");
        this.matchManager = matchManager;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {


        world.execute(() -> {


            var spawnProvider = world.getWorldConfig().getSpawnProvider();
            Transform returnPoint = (spawnProvider != null)
                    ? spawnProvider.getSpawnPoint(world, playerRef.getUuid())
                    : new Transform(0, 80, 0); // Fallback: spawn por defecto del Lobby


            CompletableFuture<World> instanceFuture =
                    this.matchManager.getOrCreateBackroomsInstance(world, returnPoint);


            InstancesPlugin.teleportPlayerToLoadingInstance(
                    ref,
                    store,
                    instanceFuture,
                    null // null = usar el returnPoint definido en getOrCreateBackroomsInstance
            );
            
            instanceFuture.thenAccept(targetWorld -> {
                if (targetWorld == null) return;
                this.matchManager.registerPlayerInInstance(targetWorld.getName(), ref);
            }).exceptionally(ex -> {
                System.err.println("[Backrooms] Error al unirse a la instancia compartida: " + ex.getMessage());
                return null;
            });
        });
    }
}
