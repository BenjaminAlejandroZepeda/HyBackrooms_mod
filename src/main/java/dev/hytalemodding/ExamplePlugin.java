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

        // === ELIMINADO ===
        // ExamplePlugin.activeMatchManager = this.matchManager;
        // TriggerEffect.getCodecRegistry().register(BackroomsStartEffect.ID, BackroomsStartEffect.class);
        //
        // Motivo: el Trigger Volume ya no dispara el inicio. El comando lo reemplaza por completo.

        // Registro del comando manual de entrada al Nivel 0.
        // El jugador escribe /iniciar_backrooms en el chat para entrar.
        this.getCommandRegistry().registerCommand(new IniciarBackroomsCommand(this.matchManager));
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) return;

        World world = ref.getStore().getExternalData().getWorld();
        System.out.println("[Backrooms] PlayerReadyEvent en: " + world.getName());

        // Caso 1: ya está en el Lobby persistente → quedarse aquí, sin hacer nada.
        // === CAMBIO respecto a versión anterior ===
        // Antes: this.matchManager.queuePlayer(ref)  ← añadía al jugador a la waitingQueue.
        // Ahora: solo se loguea. El jugador permanece en el Lobby hasta que
        //        alguien ejecute /iniciar_backrooms. No existe cola.
        if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
            System.out.println("[Backrooms] Jugador en Lobby — esperando /iniciar_backrooms.");
            return;
        }

        // Caso 2: ya está dentro de una instancia activa de Backrooms → no tocar.
        if (this.matchManager.isActiveInstance(world.getName())) {
            return;
        }

        // Caso 3: mundo base/inicial de conexión → redirigir a la instancia
        // persistente del Lobby (se crea una sola vez y se reutiliza para todos).
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

    // === ELIMINADO ===
    // private void onPlayerDisconnect(PlayerDisconnectEvent event) {
    //     this.matchManager.removePlayer(event.getPlayerRef().getReference());
    // }
    //
    // Motivo: solo se usaba para limpiar la waitingQueue. Al eliminar la cola,
    // no hay nada que limpiar al desconectarse. Las instancias vacías se destruyen
    // automáticamente en MatchManager.tick() mediante la lógica de autocleanup.

    // =========================================================================
    // Estados del ciclo de vida de una instancia
    // =========================================================================
    public enum MatchState { WAITING, PLAYING, CLEANUP }

    // =========================================================================
    // Metadata de una instancia activa
    // =========================================================================
    public static class InstanceContext {
        public final String worldName;
        // CopyOnWriteArrayList: múltiples jugadores se añaden desde distintos hilos
        // (cada /iniciar_backrooms dispara un thenAccept en el thread pool del SDK).
        // COWL es seguro para lectura frecuente (tick, isActiveInstance) y escritura
        // esporádica (un jugador nuevo entra), patrón ya usado en versiones anteriores.
        public final List<Ref<EntityStore>> players;
        public MatchState state;
        public final long createdAtMillis;

        // Evita destruir la instancia mientras el jugador está en tránsito.
        // Solo se permite limpieza por vacío DESPUÉS de que esto sea true al menos una vez.
        public volatile boolean hasBeenPopulated = false;

        public InstanceContext(String worldName) {
            this.worldName = worldName;
            this.players = new CopyOnWriteArrayList<>(); // vacía: se llena con registerPlayerInInstance()
            this.state = MatchState.PLAYING;
            this.createdAtMillis = System.currentTimeMillis();
        }
    }

    // =========================================================================
    // MatchManager: gestión del Lobby persistente + ciclo de vida de instancias
    //
    // === CAMBIOS respecto a versión anterior ===
    // ELIMINADO: registerInstance(worldName, players) — reemplazado por getOrCreateBackroomsInstance()
    //            que registra la instancia internamente al crear el mundo.
    // AÑADIDO:   activeBackroomsFuture   — cachea la instancia compartida entre todos los jugadores.
    // AÑADIDO:   getOrCreateBackroomsInstance() — synchronized: un solo spawnInstance para todos.
    // AÑADIDO:   registerPlayerInInstance()     — añade jugadores a la COWL de la instancia activa.
    // AÑADIDO:   clearActiveBackroomsInstance() — llamado en tick() al destruir la instancia,
    //            para que el próximo /iniciar_backrooms cree una nueva.
    // =========================================================================
    public static class MatchManager extends TickingSystem<EntityStore> {

        private static final long INSTANCE_POPULATION_GRACE_MS = 30_000;

        private final Map<String, InstanceContext> activeInstances = new ConcurrentHashMap<>();
        private volatile CompletableFuture<World> lobbyInstanceFuture;

        // Future de la instancia compartida de Backrooms_Nivel0.
        // Todos los jugadores que ejecuten /iniciar_backrooms entran al mismo mundo.
        // volatile: garantiza visibilidad entre hilos sin necesidad de leer dentro de synchronized.
        // Se pone a null cuando la instancia se destruye en tick() → el próximo comando crea una nueva.
        private volatile CompletableFuture<World> activeBackroomsFuture = null;

        // Nombre del mundo asociado al activeBackroomsFuture (disponible solo tras thenAccept).
        // Se usa en clearActiveBackroomsInstance() para verificar que destruimos la instancia correcta.
        private volatile String activeBackroomsWorldName = null;

        /**
         * Devuelve el Future de la instancia persistente del Lobby, creándola si es
         * la primera vez. originWorld se usa solo como anchor para spawnInstance.
         */
        public synchronized CompletableFuture<World> getOrCreateLobbyInstance(World originWorld) {
            if (this.lobbyInstanceFuture == null) {
                System.out.println("[Backrooms] Creando instancia persistente del Lobby (única vez).");
                CompletableFuture<World> future = InstancesPlugin.get().spawnInstance(
                        "Lobby",
                        originWorld,
                        new Transform(0, 80, 0) // SIN CONFIRMAR: coordenadas hacia el mundo base
                );
                future.exceptionally(ex -> {
                    System.err.println("[Backrooms] Error creando instancia Lobby: " + ex.getMessage());
                    this.resetLobbyInstance(); // Liberar cache para que el siguiente join reintente
                    return null;
                });
                this.lobbyInstanceFuture = future;
            }
            return this.lobbyInstanceFuture;
        }

        private synchronized void resetLobbyInstance() {
            this.lobbyInstanceFuture = null;
        }

        /**
         * Devuelve el Future de la instancia compartida de Backrooms_Nivel0,
         * creándola solo si no existe o si la anterior ya fue destruida.

         * Synchronized para que dos jugadores que ejecuten /iniciar_backrooms
         * al mismo tiempo no generen dos instancias distintas: el primero crea
         * el Future, el segundo entra al bloque, ve que ya existe y lo reutiliza.

         * teleportPlayerToLoadingInstance acepta un Future ya resuelto (mundo cargado)
         * casos: instancia cargando y instancia ya lista.
         */
        public synchronized CompletableFuture<World> getOrCreateBackroomsInstance(
                World lobbyWorld, Transform returnPoint) {

            // Caso A: ya hay un Future activo.
            if (activeBackroomsFuture != null) {
                if (!activeBackroomsFuture.isDone()) {
                    // Todavía cargando: devolver el mismo Future — el jugador
                    // quedará en la pantalla de carga junto al resto.
                    System.out.println("[Backrooms] Instancia cargando — sumando jugador al Future existente.");
                    return activeBackroomsFuture;
                }
                // Ya terminó de cargar: verificar si el mundo sigue vivo.
                // SIN CONFIRMAR: World.isAlive() en SDK 0.6.
                // Si no compila, reemplazar por: activeInstances.containsKey(activeBackroomsWorldName)
                World existing = activeBackroomsFuture.getNow(null);
                if (existing != null && existing.isAlive()) {
                    System.out.println("[Backrooms] Reutilizando instancia compartida: " + existing.getName());
                    return activeBackroomsFuture;
                }
                // El mundo fue destruido (tick() llamó safeRemoveInstance): crear uno nuevo.
                System.out.println("[Backrooms] Instancia anterior destruida — creando nueva instancia compartida.");
                activeBackroomsFuture = null;
                activeBackroomsWorldName = null;
            }

            // Caso B: no hay instancia activa → crear una nueva.
            System.out.println("[Backrooms] Creando instancia compartida Backrooms_Nivel0...");
            CompletableFuture<World> future = InstancesPlugin.get().spawnInstance(
                    "Backrooms_Nivel0",
                    lobbyWorld,
                    returnPoint
            );

            future.thenAccept(newWorld -> {
                if (newWorld == null) return;
                // Registrar con lista de jugadores vacía; cada jugador se añade
                // individualmente via registerPlayerInInstance() en el comando.
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

        /**
         * Añade un jugador a la lista de la instancia activa.
         * Llamado por IniciarBackroomsCommand.thenAccept() tras confirmar que el mundo cargó.
         * CopyOnWriteArrayList en InstanceContext.players garantiza thread-safety aquí.
         */
        public void registerPlayerInInstance(String worldName, Ref<EntityStore> ref) {
            InstanceContext ctx = activeInstances.get(worldName);
            if (ctx != null && !ctx.players.contains(ref)) {
                ctx.players.add(ref);
                System.out.println("[Backrooms] Jugador registrado en " + worldName
                        + " — total: " + ctx.players.size());
            }
        }

        /**
         * Limpia la referencia a la instancia compartida cuando es destruida.
         * Llamado desde tick() al eliminar la instancia, para que el próximo
         * /iniciar_backrooms cree una nueva desde cero.
         *
         * @param worldName nombre del mundo destruido, o null si falló antes de cargar.
         */
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

            // --- Rama Lobby ---
            // FIX original: comparar por prefijo, no por igualdad exacta.
            // === CAMBIO respecto a versión anterior ===
            // Eliminado el bloque que revisaba waitingQueue + MIN_LOBBY_WAIT_MS.
            // tick() ya no inicia partidas. Solo se devuelve para saltar la rama de limpieza.
            if (world.getName().startsWith(LOBBY_INSTANCE_PREFIX)) {
                return;
            }

            // --- Rama Instancia: autolimpieza cuando queda vacía ---
            // Se exige hasBeenPopulated=true antes de limpiar, para no destruir
            // instancias recién creadas durante el tránsito del jugador.
            // Evidencia de logs: ~57ms entre "Instancia registrada" y "Player joined world".
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

    // =========================================================================
    // ReturnManager: devuelve un jugador al Lobby desde una instancia
    // (sin cambios respecto a versión anterior)
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

            world.execute(() ->
                    InstancesPlugin.exitInstance(entityRef, entityStore)
                            .exceptionally(ex -> {
                                System.err.println("[Backrooms] Error en exitInstance: " + ex.getMessage());
                                return null;
                            })
            );
        }
    }

    // =========================================================================
    // Sistema de detección de muerte de jugadores en instancias Backrooms
    // (sin cambios respecto a versión anterior)
    // =========================================================================
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

            // Ignorar muertes en el Lobby o cualquier mundo no gestionado por este plugin
            if (!this.matchManager.isActiveInstance(world.getName())) return;

            PlayerRef playerRef = (PlayerRef) typedStore.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            world.execute(() -> ReturnManager.returnToLobby(playerRef));
        }
    }
}
