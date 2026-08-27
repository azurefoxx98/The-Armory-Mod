package me.ladypaladra.thearmorymod.telemetry;

import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryBootstrap;
import com.alechilles.alecstelemetry.embedded.EmbeddedTelemetryHandle;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.function.Consumer;

/**
 * Keeps telemetry failures away from the rest of the mod. The Armory must load and run exactly
 * as it does without telemetry when the telemetry runtime is missing, broken, disabled or
 * throwing. Every entry point here therefore fails closed as a no-op.
 *
 * <p>This is the only class in the mod allowed to import {@code com.alechilles}. That makes the
 * privacy rule checkable. The descriptor's allowlist drops fields we never declared, but it
 * cannot stop us from putting a player name into a field that we did declare as a string. We
 * need exactly one file to review, not a set that grows whenever somebody adds a call site.</p>
 *
 * <p>Recorded values must never contain player names, raw UUIDs, coordinates, tokens, chat or
 * inventory contents. If we genuinely need a correlation value, it must be random or hashed
 * and must never be derived from a player.</p>
 */
public final class ArmoryTelemetry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * This is volatile because the setup thread writes it once, while reads happen wherever a
     * call site runs. Since 1.2.1, the runtime's own uploads run on isolated virtual threads,
     * so this field really does cross threads.
     */
    @Nullable
    private static volatile EmbeddedTelemetryHandle handle;

    private ArmoryTelemetry() {
    }

    /**
     * Call this once at the very start of setup. That lets telemetry capture a failure in any
     * module registered afterward. The catch uses Throwable rather than RuntimeException
     * because the realistic failure is a LinkageError from a runtime that is absent or only
     * half present, and LinkageError is an Error rather than an exception.
     */
    public static void bootstrap(@Nonnull JavaPlugin plugin) {
        handle = null;

        try {
            handle = EmbeddedTelemetryBootstrap.bootstrap(plugin);
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log(
                    "Telemetry bootstrap failed. The Armory continues with telemetry disabled."
            );
        }
    }

    public static void start() {
        dispatch("start", EmbeddedTelemetryHandle::start);
    }

    public static void shutdown() {
        dispatch("shutdown", EmbeddedTelemetryHandle::shutdown);
    }

    public static void breadcrumb(@Nonnull String category, @Nonnull String detail) {
        dispatch("breadcrumb", target -> target.recordBreadcrumb(category, detail));
    }

    public static void setupFailure(@Nonnull Throwable throwable) {
        dispatch("setup failure capture", target -> target.captureSetupFailure(throwable));
    }

    public static void startFailure(@Nonnull Throwable throwable) {
        dispatch("start failure capture", target -> target.captureStartFailure(throwable));
    }

    /**
     * Callers pass plain strings so no other class needs to import the telemetry API. That
     * keeps the privacy review in this one file.
     *
     * <p>Careful with operation. It is the page event type, and its value comes from the client.
     * Today it can only be one of our constants because the pages dispatch it through a switch
     * with a default branch that does nothing. An arbitrary string therefore never reaches a
     * handler and never throws, but that safety is incidental rather than designed. If somebody
     * adds a default branch that can fail, client-supplied text will leave the mod in a crash
     * report. Filter it here, where we control what may leave the mod, instead of depending on
     * a switch statement two classes away.</p>
     *
     * <p>Nothing that touches the telemetry runtime may be constructed outside
     * {@link #dispatch}. This guard was bypassed here once, which let linkage failures escape
     * from the page failure handler it was meant to protect.</p>
     */
    public static void pageFailure(
            @Nonnull String subsystem,
            @Nonnull String operation,
            @Nonnull Throwable throwable
    ) {
        String safeSubsystem = safeToken(subsystem);
        String safeOperation = safeToken(operation);
        dispatch(
                "page failure capture",
                target -> {
                    TelemetryEventContext context = TelemetryEventContext.error()
                            .subsystem(safeSubsystem)
                            .operation(safeOperation)
                            .featureKey(safeSubsystem + "_table")
                            .runtimeSide("server")
                            .build();
                    target.recordErrorWithContext("page_event_failed", throwable, context);
                }
        );
    }

    public static void error(
            @Nonnull String name,
            @Nonnull Throwable throwable,
            @Nonnull String detail
    ) {
        dispatch("error capture", target -> target.recordError(name, throwable, detail));
    }

    /**
     * Replace anything that is not a plain identifier instead of sending it. Player-written text
     * on this mod's surfaces is free form. Item names, descriptions and a search box all accept
     * spaces, punctuation and colour markup, so none can survive this filter. The length is also
     * capped because a value long enough to matter is carrying something.
     *
     * <p>This deliberately uses a whitelist rather than a blacklist. A blacklist has to predict
     * what a player might type, and it will eventually be wrong.</p>
     */
    @Nonnull
    static String safeToken(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > 40) {
            return "unknown";
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            if (!allowed) {
                return "unknown";
            }
        }

        return value;
    }

    /**
     * This gives the null check and catch one home instead of copying both into every forwarding
     * method. With six copies of a safety wrapper, one will eventually miss a check. A later
     * call site cannot forget a guard that it never has to write.
     *
     * <p>This catches Throwable on purpose, with a deliberate tradeoff. Catching Error is
     * normally wrong because it hides a dying JVM. But this class exists specifically to
     * contain LinkageError. The runtime is bundled, so a class can resolve at bootstrap and
     * still fail to link on first real use, sending the failure straight from a call site into
     * the engine tick. Nothing is silent because every failure is logged with its cause. A mod
     * shipped to real server owners must not die because its telemetry hiccupped.</p>
     */
    private static void dispatch(
            @Nonnull String what,
            @Nonnull Consumer<EmbeddedTelemetryHandle> action
    ) {
        EmbeddedTelemetryHandle target = handle;
        if (target == null) {
            return;
        }

        try {
            action.accept(target);
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log("Telemetry %s failed.", what);
        }
    }
}
