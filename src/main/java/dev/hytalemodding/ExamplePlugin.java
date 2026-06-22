package dev.hytalemodding;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.commands.IniciarBackroomsCommand;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ExamplePlugin extends JavaPlugin {

    private static final String LOBBY_WORLD_NAME = "Lobby";

    // spawnInstance("Lobby", ...) produce mundos con nombre "instance-Lobby-<uuid>".
    // Confirmado por logs. Si el patrón cambia en futuras versiones del SDK, actualizar.
    private static final String LOBBY_INSTANCE_PREFIX = "instance-" + LOBBY_WORLD_NAME + "-";

    private MatchManager matchManager;

    // === ELIMINADO ===
    // private static volatile MatchManager activeMatchManager;
    // public static MatchManager getMatchManagerInstance() { ... }
    //
    // Motivo: solo lo usaba BackroomsStartEffect para acceder al MatchManager sin
    // inyección de dependencias. Al eliminar BackroomsStartEffect, este patrón
    // estático ya no es necesario. IniciarBackroomsCommand recibe el MatchManager
    // por constructor, que es la forma correcta.

    public ExamplePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.matchManager = new MatchManager();

        this.getEntityStoreRegistry().registerSystem(this.matchManager);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEntityStoreRegistry().registerSystem(new BackroomsDeathSystem(this.matchManager));
        this.getCommandRegistry().registerCommand(new IniciarBackroomsCommand(this.matchManager));
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        World world = ref.getStore().getExternalData().getWorld();
        System.out.println("[Backrooms] PlayerReadyEvent en: " + world.getName());
        if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
            System.out.println("[Backrooms] Jugador en Lobby — esperando /iniciar_backrooms.");
            return;
        }


        if (this.matchManager.isActiveInstance(world.getName())) {
            return;
        }

        System.out.println("[Backrooms] Redirigiendo al Lobby persistente desde: " + world.getName());
        world.execute(() -> {
            CompletableFuture<World> lobbyFuture = this.matchManager.getOrCreateLobbyInstance(world);
            InstancesPlugin.teleportPlayerToLoadingInstance(
                    ref,
                    ref.getStore(),
                    lobbyFuture,
                    null
            );
        });
    }

    public enum MatchState { WAITING, PLAYING, CLEANUP }

    // =========================================================================
    // Metadata de una instancia activa
    // =========================================================================
    public static class InstanceContext {
        public final String worldName;
        public final List<Ref<EntityStore>> players;
        public MatchState state;
        public final long createdAtMillis;


        public volatile boolean hasBeenPopulated = false;

        public InstanceContext(String worldName) {
            this.worldName = worldName;
            this.players = new CopyOnWriteArrayList<>();
            this.state = MatchState.PLAYING;
            this.createdAtMillis = System.currentTimeMillis();
        }
    }

    public static class MatchManager extends TickingSystem<EntityStore> {

        private static final long INSTANCE_POPULATION_GRACE_MS = 30_000;

        private final Map<String, InstanceContext> activeInstances = new ConcurrentHashMap<>();
        private volatile CompletableFuture<World> lobbyInstanceFuture;

        private volatile CompletableFuture<World> activeBackroomsFuture = null;


        private volatile String activeBackroomsWorldName = null;


        public synchronized CompletableFuture<World> getOrCreateLobbyInstance(World originWorld) {
            if (this.lobbyInstanceFuture == null) {
                System.out.println("[Backrooms] Creando instancia persistente del Lobby (única vez).");
                CompletableFuture<World> future = InstancesPlugin.get().spawnInstance(
                        "Lobby",
                        originWorld,
                        new Transform(0, 80, 0)
                );
                future.exceptionally(ex -> {
                    System.err.println("[Backrooms] Error creando instancia Lobby: " + ex.getMessage());
                    this.resetLobbyInstance();
                    return null;
                });
                this.lobbyInstanceFuture = future;
            }
            return this.lobbyInstanceFuture;
        }

        private synchronized void resetLobbyInstance() {
            this.lobbyInstanceFuture = null;
        }


        public synchronized CompletableFuture<World> getOrCreateBackroomsInstance(
                World lobbyWorld, Transform returnPoint) {


            if (activeBackroomsFuture != null) {
                if (!activeBackroomsFuture.isDone()) {
                    System.out.println("[Backrooms] Instancia cargando — sumando jugador al Future existente.");
                    return activeBackroomsFuture;
                }

                World existing = activeBackroomsFuture.getNow(null);
                if (existing != null && existing.isAlive()) {
                    System.out.println("[Backrooms] Reutilizando instancia compartida: " + existing.getName());
                    return activeBackroomsFuture;
                }

                System.out.println("[Backrooms] Instancia anterior destruida — creando nueva instancia compartida.");
                activeBackroomsFuture = null;
                activeBackroomsWorldName = null;
            }


            System.out.println("[Backrooms] Creando instancia compartida Backrooms_Nivel0...");
            CompletableFuture<World> future = InstancesPlugin.get().spawnInstance(
                    "Backrooms_Nivel0",
                    lobbyWorld,
                    returnPoint
            );

            future.thenAccept(newWorld -> {
                if (newWorld == null) return;

                activeInstances.put(newWorld.getName(), new InstanceContext(newWorld.getName()));
                activeBackroomsWorldName = newWorld.getName();
                System.out.println("[Backrooms] Instancia compartida lista: " + newWorld.getName());
            }).exceptionally(ex -> {
                System.err.println("[Backrooms] Error creando instancia compartida: " + ex.getMessage());
                clearActiveBackroomsInstance(null); // Liberar para permitir reintento
                return null;
            });

            activeBackroomsFuture = future;
            return future;
        }


        public void registerPlayerInInstance(String worldName, Ref<EntityStore> ref) {
            InstanceContext ctx = activeInstances.get(worldName);
            if (ctx != null && !ctx.players.contains(ref)) {
                ctx.players.add(ref);
                System.out.println("[Backrooms] Jugador registrado en " + worldName
                        + " — total: " + ctx.players.size());
            }
        }


        public synchronized void clearActiveBackroomsInstance(String worldName) {
            if (worldName == null || worldName.equals(activeBackroomsWorldName)) {
                activeBackroomsFuture = null;
                activeBackroomsWorldName = null;
                System.out.println("[Backrooms] Referencia de instancia compartida liberada.");
            }
        }

        /** Consultado por BackroomsDeathSystem para ignorar muertes fuera del Backrooms. */
        public boolean isActiveInstance(String worldName) {
            return activeInstances.containsKey(worldName);
        }

        @Override
        public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
            World world = store.getExternalData().getWorld();


            if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
                return;
            }


            InstanceContext ctx = activeInstances.get(world.getName());
            if (ctx != null) {
                boolean currentlyEmpty = world.getPlayerRefs().isEmpty();

                if (!currentlyEmpty) {
                    ctx.hasBeenPopulated = true;
                } else {
                    boolean genuinelyAbandoned = ctx.hasBeenPopulated;
                    boolean neverArrivedTimeout = !ctx.hasBeenPopulated
                            && (System.currentTimeMillis() - ctx.createdAtMillis) > INSTANCE_POPULATION_GRACE_MS;

                    if (genuinelyAbandoned || neverArrivedTimeout) {
                        String reason = neverArrivedTimeout
                                ? "nunca recibió jugadores (timeout)"
                                : "vaciada tras uso normal";
                        world.execute(() -> {
                            System.out.println("[Backrooms] Instancia vacía, eliminando ("
                                    + reason + "): " + world.getName());
                            activeInstances.remove(world.getName());
                            // Liberar la referencia cacheada para que el próximo
                            // /iniciar_backrooms cree una instancia nueva y limpia.
                            clearActiveBackroomsInstance(world.getName());
                            InstancesPlugin.safeRemoveInstance(world);
                        });
                    }
                }
            }
        }
    }


    public static class ReturnManager {

        /**
         * Retorna al jugador al Lobby usando el returnPoint definido en spawnInstance.
         * Firma de exitInstance confirmada en ExampleExitInstanceCommand (docs_sueltos.txt):
         * exitInstance(Ref<EntityStore>, Store<EntityStore>) — estático.
         */
        public static void returnToLobby(PlayerRef playerRef) {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) return;

            Store<EntityStore> entityStore = entityRef.getStore();
            World world = entityStore.getExternalData().getWorld();

            world.execute(() ->
                    InstancesPlugin.exitInstance(entityRef, entityStore)
                            .exceptionally(ex -> {
                                System.err.println("[Backrooms] Error en exitInstance: " + ex.getMessage());
                                return null;
                            })
            );
        }
    }

    public static class BackroomsDeathSystem extends DeathSystems.OnDeathSystem {

        private final MatchManager matchManager;

        public BackroomsDeathSystem(MatchManager matchManager) {
            this.matchManager = matchManager;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType());
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComponentAdded(@Nonnull Ref ref, @Nonnull DeathComponent component,
                                     @Nonnull Store store, @Nonnull CommandBuffer commandBuffer) {

            Store<EntityStore> typedStore = (Store<EntityStore>) store;
            World world = typedStore.getExternalData().getWorld();

            if (!this.matchManager.isActiveInstance(world.getName())) return;

            PlayerRef playerRef = (PlayerRef) typedStore.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            world.execute(() -> ReturnManager.returnToLobby(playerRef));
        }
    }
}
