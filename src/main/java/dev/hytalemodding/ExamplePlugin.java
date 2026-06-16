package dev.hytalemodding;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ExamplePlugin extends JavaPlugin {

    // 1. Usamos una constante para evitar fragilidad en el nombre [User Advice]
    private static final String LOBBY_WORLD_NAME = "Lobby";
    private MatchManager matchManager;

    public ExamplePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.matchManager = new MatchManager();
        this.getEntityStoreRegistry().registerSystem(this.matchManager);

        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        World world = ref.getStore().getExternalData().getWorld();

        // LOG DE DIAGNÓSTICO [User Request]
        System.out.println("DEBUG: PlayerReady en el mundo: " + world.getName());

        // 2. FILTRO CRÍTICO: Solo registrar si el jugador está LISTO en el LOBBY
        if (world.getName().equalsIgnoreCase(LOBBY_WORLD_NAME)) {
            this.matchManager.registerPlayer(ref);
        }
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        this.matchManager.unregisterPlayer(event.getPlayerRef().getReference());
    }

    public enum MatchState { WAITING, STARTING, PLAYING, CLEANUP }

    public static class MatchManager extends TickingSystem<EntityStore> {
        private MatchState state = MatchState.WAITING;
        private final List<Ref<EntityStore>> waitingPlayers = new ArrayList<>();
        private World activeInstance = null;

        @Override
        public void tick(float dt, int index, Store<EntityStore> store) {
            World world = store.getExternalData().getWorld();
            if (!world.getName().equalsIgnoreCase(LOBBY_WORLD_NAME)) return;

            // Iniciar partida
            if (state == MatchState.WAITING && !waitingPlayers.isEmpty()) {
                this.state = MatchState.STARTING; // Bloqueo inmediato
                world.execute(() -> startMatch(world));
            }

            // 3. LOGICA DE REINICIO: Si la instancia se quedó vacía, limpiar y volver a WAITING [3, 6]
            if (state == MatchState.PLAYING && activeInstance != null) {
                if (activeInstance.getPlayerRefs().isEmpty()) {
                    this.state = MatchState.CLEANUP;
                    activeInstance.execute(this::cleanupMatch);
                }
            }
        }

        private void startMatch(World lobbyWorld) {
            InstancesPlugin instances = InstancesPlugin.get();
            Transform returnPoint = new Transform(0.5, 80.0, 0.5);

            CompletableFuture<World> level0Future = instances.spawnInstance(
                    "Backrooms_Nivel0",
                    lobbyWorld,
                    returnPoint
            );

            level0Future.thenAccept(world -> this.activeInstance = world);

            for (Ref<EntityStore> playerRef : waitingPlayers) {
                if (playerRef.isValid()) {
                    InstancesPlugin.teleportPlayerToLoadingInstance(
                            playerRef, playerRef.getStore(), level0Future, null);
                }
            }

            waitingPlayers.clear();
            this.state = MatchState.PLAYING;
        }

        private void cleanupMatch() {
            if (activeInstance == null) return;

            System.out.println("Limpiando instancia: " + activeInstance.getName());

            // 4. LIMPIEZA OBLIGATORIA: safeRemoveInstance libera la RAM [3, 7]
            InstancesPlugin.get().safeRemoveInstance(activeInstance);

            this.activeInstance = null;
            this.state = MatchState.WAITING; // EL CICLO SE REINICIA AQUÍ [6]
        }

        public void registerPlayer(Ref<EntityStore> player) {
            if (state == MatchState.WAITING && !waitingPlayers.contains(player)) {
                waitingPlayers.add(player);
            }
        }

        public void unregisterPlayer(Ref<EntityStore> player) {
            waitingPlayers.remove(player);
        }
    }
}