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

    // Evidencia real de logs: spawnInstance("Lobby", ...) produce mundos nombrados
    // "instance-Lobby-<uuid>", NO "Lobby" exacto. Confirmado por logs del usuario,
    // no por documentación. Si el patrón de nombrado cambia en otra versión del SDK,
    // este prefijo deja de ser válido y hay que volver a loguear world.getName().
    private static final String LOBBY_INSTANCE_PREFIX = "instance-" + LOBBY_WORLD_NAME + "-";

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

        // Caso 1: ya está dentro de la instancia persistente del Lobby -> matchmaking.
        // FIX: el nombre real no es "Lobby" exacto, es "instance-Lobby-<uuid>"
        // (confirmado por logs). Se compara por prefijo, no por igualdad.
        if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
            this.matchManager.queuePlayer(ref);
            return;
        }

        // Caso 2: ya está dentro de una partida de Backrooms activa -> no tocar.
        if (this.matchManager.isActiveInstance(world.getName())) {
            return;
        }

        // Caso 3: mundo base/inicial de conexión -> redirigir a la instancia
        // persistente del Lobby (se crea una sola vez y se reutiliza para todos).
        // FIX: envuelto en world.execute() — la misma regla de hilos que ya
        // aplicamos a startMatch() aplica aquí; se había perdido en la ronda anterior.
        System.out.println("[Backrooms] Redirigiendo al Lobby persistente desde: " + world.getName());
        world.execute(() -> {
            CompletableFuture<World> lobbyFuture = this.matchManager.getOrCreateLobbyInstance(world);
            InstancesPlugin.teleportPlayerToLoadingInstance(
                    ref,
                    ref.getStore(),
                    lobbyFuture,
                    null // Override de returnPoint: sin confirmar coordenadas del mundo base
            );
        });
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
        public final long createdAtMillis;

        // Evita destruir la instancia mientras el jugador todavía está en tránsito
        // (teleportPlayerToLoadingInstance deja a la instancia momentáneamente vacía
        // entre el momento en que se registra el contexto y el momento en que el
        // jugador realmente entra). Solo se permite limpieza por vacío DESPUÉS de
        // que esto sea true al menos una vez.
        public volatile boolean hasBeenPopulated = false;

        public InstanceContext(String worldName, List<Ref<EntityStore>> players) {
            this.worldName = worldName;
            this.players = new ArrayList<>(players);
            this.state = MatchState.PLAYING;
            this.createdAtMillis = System.currentTimeMillis();
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

        // Si una instancia nunca llega a recibir a su primer jugador (p. ej. el
        // teleport falló silenciosamente en el cliente), este timeout evita que
        // quede huérfana para siempre. No afecta el caso normal: el jugador suele
        // entrar en menos de un segundo, muy por debajo de este margen.
        private static final long INSTANCE_POPULATION_GRACE_MS = 30_000;

        // Clave por nombre de mundo. world.getUuid() NO existe en SDK 0.6.
        private final Map<String, InstanceContext> activeInstances = new ConcurrentHashMap<>();

        // Instancia única y persistente del Lobby. Se crea una sola vez (perezosamente,
        // en el primer join) y se reutiliza para todos los jugadores. Protegida con
        // synchronized para evitar que dos joins concurrentes generen dos Lobbys.
        private volatile CompletableFuture<World> lobbyInstanceFuture;

        /**
         * Devuelve el Future de la instancia persistente del Lobby, creándola si es
         * la primera vez que se solicita. originWorld se usa solo como contexto/anchor
         * para spawnInstance (mundo base de conexión), no como destino.
         */
        public synchronized CompletableFuture<World> getOrCreateLobbyInstance(World originWorld) {
            if (this.lobbyInstanceFuture == null) {
                System.out.println("[Backrooms] Creando instancia persistente del Lobby (única vez).");
                // SIN CONFIRMAR: coordenadas de returnPoint hacia el mundo base.
                CompletableFuture<World> future = InstancesPlugin.get().spawnInstance(
                        "Lobby",
                        originWorld,
                        new Transform(0, 80, 0)
                );
                // FIX: si la creación falla, liberar el cache para que el siguiente
                // join reintente en vez de quedar atado a un future roto para siempre.
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
            // FIX: mismo bug que en onPlayerReady — comparar por prefijo, no por
            // igualdad exacta, ya que el nombre real es "instance-Lobby-<uuid>".
            if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
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
            // FIX: el chequeo anterior (containsKey + isEmpty) destruía instancias
            // recién creadas durante la ventana en que el jugador todavía está en
            // tránsito (evidencia de logs: ~57ms entre "Instancia registrada" y
            // "Player joined world"). Ahora se exige que la instancia haya sido
            // confirmada como poblada al menos una vez antes de poder limpiarla,
            // salvo timeout de seguridad si nunca llega nadie.
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
                            InstancesPlugin.safeRemoveInstance(world);
                        });
                    }
                    // si no se cumple ninguna condición: sigue en ventana de gracia, no hacer nada.
                }
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