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

        // world.execute() garantiza Thread Safety: encola la operación en el hilo
        // del mundo aunque AbstractPlayerCommand ya corra en él, protegiendo el acceso
        // a getOrCreateBackroomsInstance() si dos jugadores ejecutan el comando a la vez.
        world.execute(() -> {

            // 1. Definir el returnPoint usando el ISpawnProvider del Lobby actual.
            //    El SDK usa este punto para devolver al jugador si muere o sale.
            //    SIN CONFIRMAR: firma exacta de getSpawnPoint(World, UUID) en SDK 0.6.
            //    Si no compila, probar getEntityId() en lugar de getUuid().
            var spawnProvider = world.getWorldConfig().getSpawnProvider();
            Transform returnPoint = (spawnProvider != null)
                    ? spawnProvider.getSpawnPoint(world, playerRef.getUuid())
                    : new Transform(0, 80, 0); // Fallback: spawn por defecto del Lobby

            // 2. Obtener la instancia compartida (o crearla si no existe / fue destruida).
            //    getOrCreateBackroomsInstance() es synchronized: si dos jugadores llaman
            //    a la vez, el segundo espera al primero y recibe el mismo Future,
            //    garantizando que NUNCA se creen dos instancias simultáneas.
            CompletableFuture<World> instanceFuture =
                    this.matchManager.getOrCreateBackroomsInstance(world, returnPoint);

            // 3. Encolar el teletransporte ANTES de que el Future resuelva.
            //    - Si la instancia aún carga: el jugador ve la pantalla de carga y entra al terminar.
            //    - Si la instancia ya cargó:  el SDK teletransporta al instante.
            //    Llamarlo dentro de thenAccept (post-carga) puede hacer que el SDK
            //    no encuentre la ventana de carga y devuelva al jugador al Lobby.
            InstancesPlugin.teleportPlayerToLoadingInstance(
                    ref,
                    store,
                    instanceFuture,
                    null // null = usar el returnPoint definido en getOrCreateBackroomsInstance
            );

            // 4. Registrar al jugador en la lista de la instancia una vez confirmada la carga.
            //    Necesario para que BackroomsDeathSystem sepa devolver a ESTE jugador al Lobby.
            //    thenAccept se dispara en el thread pool del SDK, de ahí que InstanceContext
            //    use CopyOnWriteArrayList para la lista de jugadores.
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
