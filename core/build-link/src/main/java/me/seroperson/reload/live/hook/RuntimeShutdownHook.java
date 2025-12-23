package me.seroperson.reload.live.hook;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.seroperson.reload.live.build.BuildLogger;
import me.seroperson.reload.live.settings.DevServerSettings;

/**
 * Generic runtime shutdown hook that runs application shutdown hooks.
 *
 * <p>This hook is designed to gracefully shut down applications by running all registered shutdown
 * hooks. It's a fallback option that works with any Java application regardless of the specific
 * framework used.
 */
public class RuntimeShutdownHook implements Hook {

  // We need to preserve build-system shutdown hooks
  private final Map<Thread, Thread> buildSystemShutdownHooks;
  private final Set<Long> buildSystemHookThreadIds;

  public RuntimeShutdownHook() {
    buildSystemShutdownHooks = new IdentityHashMap<>(ReflectionUtils.getRegistredShutdownHooks());
    buildSystemHookThreadIds =
        buildSystemShutdownHooks.keySet().stream().map(Thread::getId).collect(Collectors.toSet());
  }

  @Override
  public String description() {
    return "Shutdown a generic application";
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public void hook(Thread th, ClassLoader cl, DevServerSettings settings, BuildLogger logger) {
    logger.debug("Skipping shutdown hooks:");
    ReflectionUtils.logShutdownHooks(buildSystemShutdownHooks, logger);
    // Unregistering build-system shutdown hooks
    ReflectionUtils.unregisterShutdownHooks(buildSystemHookThreadIds);
    ReflectionUtils.runApplicationShutdownHooks(logger);
    // Reset the hooks field to the initial state to prevent
    // the application from thinking it's permanently in shutdown state
    ReflectionUtils.setShutdownHooks(new IdentityHashMap<>(buildSystemShutdownHooks));
  }
}
