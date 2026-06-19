package dev.hytalemodding;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ExamplePlugin extends JavaPlugin {

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
        this.getEntityStoreRegistry().registerSystem(new BackroomsDeathSystem(this.matchManager));
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        World world = ref.getStore().getExternalData().getWorld();
        System.out.println("[Backrooms] PlayerReadyEvent en: " + world.getName());

        // ANTI-LOOP: PlayerReadyEvent es global y se dispara también cuando el jugador
        // entra a Backrooms_Nivel0. Sin este filtro, el jugador sería re-encolado
        // desde dentro de la instancia y teletransportado de vuelta al Lobby de inmediato.
        if (world.getName().equals(LOBBY_WORLD_NAME)) {
            this.matchManager.queuePlayer(ref);
        }
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        // SIN CONFIRMAR: tipo exacto de PlayerDisconnectEvent.getPlayerRef() en SDK 0.6.
        // .getReference() se mantiene por compatibilidad con versiones anteriores del SDK.
        this.matchManager.removePlayer(event.getPlayerRef().getReference());
    }

    // =========================================================================
    // Estados del ciclo de vida de una instancia
    // =========================================================================
    public enum MatchState { WAITING, PLAYING, CLEANUP }

    // =========================================================================
    // Metadata de una instancia activa
    // =========================================================================
    public static class InstanceContext {
        public final String worldName;
        public final List<Ref<EntityStore>> players;
        public MatchState state;

        public InstanceContext(String worldName, List<Ref<EntityStore>> players) {
            this.worldName = worldName;
            this.players = new ArrayList<>(players);
            this.state = MatchState.PLAYING;
        }
    }

    // =========================================================================
    // MatchManager: cola de jugadores + ciclo de vida de instancias
    // =========================================================================
    public static class MatchManager extends TickingSystem<EntityStore> {

        // CopyOnWriteArrayList: leída frecuentemente en tick (mundo),
        // escrita raramente desde hilos de red (eventos join/disconnect).
        // Patrón confirmado en fuentes: ConcurrentHashMap, synchronized y COWL en SDK.
        private final CopyOnWriteArrayList<Ref<EntityStore>> waitingQueue =
                new CopyOnWriteArrayList<>();

        // Clave por nombre de mundo. world.getUuid() NO existe en SDK 0.6.
        private final Map<String, InstanceContext> activeInstances = new ConcurrentHashMap<>();

        public void queuePlayer(Ref<EntityStore> player) {
            if (!waitingQueue.contains(player)) {
                waitingQueue.add(player);
            }
        }

        public void removePlayer(Ref<EntityStore> player) {
            waitingQueue.remove(player);
        }

        /** Consultado por BackroomsDeathSystem para ignorar muertes fuera del Backrooms. */
        public boolean isActiveInstance(String worldName) {
            return activeInstances.containsKey(worldName);
        }

        @Override
        public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
            World world = store.getExternalData().getWorld();

            // --- Rama Lobby ---
            if (world.getName().equals(LOBBY_WORLD_NAME)) {
                if (!waitingQueue.isEmpty()) {
                    // Snapshot atómico + vaciado inmediato antes de world.execute().
                    // Garantiza que ticks subsiguientes no relancen otro startMatch
                    // con los mismos jugadores mientras el anterior está en curso.
                    List<Ref<EntityStore>> snapshot = new ArrayList<>(waitingQueue);
                    waitingQueue.removeAll(snapshot);
                    world.execute(() -> startMatch(world, snapshot));
                }
                return; // No ejecutar rama de limpieza sobre el Lobby
            }

            // --- Rama Instancia: autolimpieza cuando queda vacía ---
            if (activeInstances.containsKey(world.getName()) && world.getPlayerRefs().isEmpty()) {
                world.execute(() -> {
                    System.out.println("[Backrooms] Instancia vacía, eliminando: " + world.getName());
                    activeInstances.remove(world.getName());
                    InstancesPlugin.safeRemoveInstance(world); // Estático — confirmado en SDK
                });
            }
        }

        private void startMatch(World lobbyWorld, List<Ref<EntityStore>> players) {
            System.out.println("[Backrooms] Iniciando instancia para " + players.size() + " jugador(es).");

            // Tercer argumento: returnPoint en el Lobby al que vuelven los jugadores al salir.
            // El punto de SPAWN dentro de Backrooms_Nivel0 se define en instance.bson,
            // no en este parámetro. El cuarto argumento de teleportPlayerToLoadingInstance
            // (null) es el override opcional de returnPoint, documentado como tal en SDK.
            // SIN CONFIRMAR: coordenadas exactas del punto de retorno en el Lobby.
            CompletableFuture<World> instanceFuture = InstancesPlugin.get().spawnInstance(
                    "Backrooms_Nivel0",
                    lobbyWorld,
                    new Transform(0, 50, 0)
            );

            // Encolar teletransporte ANTES de que el Future resuelva.
            // teleportPlayerToLoadingInstance está diseñado para llamarse mientras el mundo carga.
            // Llamarlo dentro de thenAccept (post-carga) podría causar que el SDK
            // no encuentre la instancia en estado "loading" y devuelva al jugador al Lobby.
            for (Ref<EntityStore> pRef : players) {
                if (pRef.isValid()) {
                    InstancesPlugin.teleportPlayerToLoadingInstance(
                            pRef,
                            pRef.getStore(),
                            instanceFuture,
                            null // Override de returnPoint: null = usar el definido en spawnInstance
                    );
                }
            }

            // Registrar contexto cuando la instancia termine de cargar (post-carga)
            instanceFuture.thenAccept(newWorld -> {
                if (newWorld == null) return;
                activeInstances.put(newWorld.getName(), new InstanceContext(newWorld.getName(), players));
                System.out.println("[Backrooms] Instancia registrada: " + newWorld.getName());

            }).exceptionally(ex -> {
                // Si la carga falla: re-encolar solo jugadores que siguen conectados
                System.err.println("[Backrooms] Error cargando instancia: " + ex.getMessage());
                players.stream()
                        .filter(Ref::isValid)
                        .forEach(this::queuePlayer);
                return null;
            });
        }
    }

    // =========================================================================
    // ReturnManager: devuelve un jugador al Lobby desde una instancia
    // =========================================================================
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

            // Despachar en el hilo del mundo para garantizar coherencia ECS
            world.execute(() ->
                    InstancesPlugin.exitInstance(entityRef, entityStore) // Estático — confirmado
                            .exceptionally(ex -> {
                                System.err.println("[Backrooms] Error en exitInstance: " + ex.getMessage());
                                return null;
                            })
            );
        }
    }

    // =========================================================================
    // Sistema de detección de muerte de jugadores en instancias Backrooms
    // =========================================================================
    public static class BackroomsDeathSystem extends DeathSystems.OnDeathSystem {

        private final MatchManager matchManager;

        public BackroomsDeathSystem(MatchManager matchManager) {
            this.matchManager = matchManager;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            // Restringe el sistema a entidades Player exclusivamente.
            // Confirmado: TestDeathSystem en docs_sueltos.txt usa exactamente este patrón.
            return Query.and(Player.getComponentType());
        }

        @Override
        @SuppressWarnings("unchecked")
        // La firma de onComponentAdded usa tipos crudos porque así está definida en
        // DeathSystems.OnDeathSystem en SDK 0.6. Confirmado literalmente en TestDeathSystem
        // (docs_sueltos.txt). Los casts son seguros dado que getQuery() ya filtra solo Players.
        public void onComponentAdded(@Nonnull Ref ref, @Nonnull DeathComponent component,
                                     @Nonnull Store store, @Nonnull CommandBuffer commandBuffer) {

            Store<EntityStore> typedStore = (Store<EntityStore>) store;
            World world = typedStore.getExternalData().getWorld();

            // Ignorar muertes en el Lobby o cualquier mundo no gestionado por este plugin
            if (!this.matchManager.isActiveInstance(world.getName())) return;

            // Obtener PlayerRef directamente desde el ECS.
            // Confirmado: store.getComponent(ref, PlayerRef.getComponentType()) en fuentes.
            PlayerRef playerRef = (PlayerRef) typedStore.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            // Despachar en el hilo del mundo (ReturnManager también lo hace internamente,
            // pero se envuelve aquí para consistencia con el patrón del SDK)
            world.execute(() -> ReturnManager.returnToLobby(playerRef));
        }
    }
}