package me.seroperson.reload.live.webserver;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import me.seroperson.reload.live.ReloadGeneration;
import me.seroperson.reload.live.build.BuildLink;
import me.seroperson.reload.live.build.BuildLogger;
import me.seroperson.reload.live.build.ReloadableServer;
import me.seroperson.reload.live.hook.Hook;
import me.seroperson.reload.live.settings.DevServerSettings;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

public class DevServerStart implements ReloadableServer {

  private final AtomicBoolean isRunning = new AtomicBoolean(true);
  private final Undertow server;
  private XnioWorker currentGenerationWorker;
  private ReloadableProxyClient proxyClientProvider;
  private ThreadGroup appThreadGroup;
  private Thread appThread;

  private ClassLoader classLoader;
  private final String mainClass;

  private final List<Hook> startupHooks;
  private final List<Hook> shutdownHooks;

  private final DevServerSettings settings;
  private final BuildLogger logger;
  private final BuildLink buildLink;

  private static final AccessControlContext accessControlContext = AccessController.getContext();

  public DevServerStart(
      DevServerSettings settings,
      BuildLink buildLink,
      BuildLogger logger,
      String mainClass,
      List<String> startupHookClasses,
      List<String> shutdownHookClasses) {
    this.settings = settings;
    this.mainClass = mainClass;
    this.buildLink = buildLink;
    this.logger = logger;

    startupHooks =
        startupHookClasses.stream()
            .map(this::initHook)
            .filter(Objects::nonNull)
            .filter(Hook::isAvailable)
            .toList();
    shutdownHooks =
        shutdownHookClasses.stream()
            .map(this::initHook)
            .filter(Objects::nonNull)
            .filter(Hook::isAvailable)
            .toList();

    logger.info("Found " + startupHooks.size() + " startup hooks:");
    startupHooks.stream()
        .map((v) -> "- " + v.getClass().getSimpleName() + ": " + v.description())
        .forEach(logger::info);
    logger.info("Found " + shutdownHooks.size() + " shutdown hooks:");
    shutdownHooks.stream()
        .map((v) -> "- " + v.getClass().getSimpleName() + ": " + v.description())
        .forEach(logger::info);

    if (!settings.isDebug()) {
      silenceJboss();
    }

    createCurrentGenerationWorker();

    this.proxyClientProvider =
        new ReloadableProxyClient(
            logger, URI.create("http://" + settings.getHttpHost() + ":" + settings.getHttpPort()));
    this.proxyClientProvider.setCurrentGenerationWorker(currentGenerationWorker);

    var proxyHandler =
        new ProxyHandler(
            proxyClientProvider, 4000, ResponseCodeHandler.HANDLE_404, false, false, 2);

    var handler = new ReloadHandler(logger, this, proxyHandler);

    server =
        Undertow.builder()
            .addHttpListener(settings.getProxyHttpPort(), settings.getProxyHttpHost())
            .setHandler(handler)
            .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 1000)
            .build();
    server.start();

    appThreadGroup = new ThreadGroup("app");
  }

  private Hook initHook(String className) {
    try {
      return (Hook) Class.forName(className).getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException
        | InstantiationException
        | InvocationTargetException
        | IllegalAccessException
        | NoSuchMethodException e) {
      logger.error("Unable to initialize hook: " + className, e);
      return null;
    }
  }

  private synchronized void startInternal(ReloadGeneration generation) {
    createCurrentGenerationWorker();
    proxyClientProvider.setCurrentGenerationWorker(currentGenerationWorker);

    this.classLoader = generation.getReloadedClassLoader();
    this.appThread =
        new Thread(
            appThreadGroup,
            () -> {
              logger.info("🚀 Starting " + mainClass);
              try {
                Class<?> clazz = classLoader.loadClass(mainClass);
                var mainMethod = clazz.getMethod("main", String[].class);
                var currentThread = Thread.currentThread();
                logger.debug(
                    "Running with Context ClassLoader: "
                        + currentThread.getContextClassLoader()
                        + " in thread "
                        + currentThread);
                mainMethod.invoke(null, (Object) new String[0]);
                logger.debug("After Application.main(String[]) execution");
              } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                logger.error("Failed to invoke main method on " + mainClass, e);
                stopInternal();
                throw new RuntimeException(e);
              } catch (InvocationTargetException e) {
                if (e.getCause() instanceof InterruptedException) {
                  // Don't log InterruptedException, as likely they're intended
                } else {
                  logger.error("Error in application main thread", e);
                }
              }
            },
            "main");
    appThread.setContextClassLoader(classLoader);
    appThread.start();

    runHooks(appThread, classLoader, startupHooks);
  }

  private synchronized void stopInternal() {
    currentGenerationWorker.shutdownNow();

    if (appThread != null) {
      logger.debug("Stopping " + mainClass);
      runHooks(appThread, classLoader, shutdownHooks);
      appThread = null;
    }

    if (classLoader != null) {
      logger.debug("Cleaning up old ClassLoader");
      if (classLoader instanceof Closeable) {
        try {
          ((Closeable) classLoader).close();
        } catch (Exception e) {
          logger.error("Failed to close class loader", e);
        }
      }
      classLoader = null;
      System.gc();
    }
  }

  private void runHooks(Thread th, ClassLoader cl, List<Hook> hooks) {
    hooks.forEach(
        (v) -> {
          var hookClassName = v.getClass().getSimpleName();
          logger.debug("Running " + hookClassName);
          long start = System.currentTimeMillis();
          v.hook(th, cl, settings, logger);
          long time = System.currentTimeMillis() - start;
          logger.debug(hookClassName + " took " + time + "ms");
        });
  }

  @Override
  public synchronized void close() throws IOException {
    if (isRunning.get()) {
      logger.info("🛑 Stopping the application");
      server.stop();
      if (appThread != null) {
        stopInternal();
      }
      buildLink.close();
      isRunning.set(false);
      logger.debug("Application and proxy server were successfully stopped");
    }
  }

  @Override
  public boolean reload() {
    var reloadResult = buildLink.reload();
    if (reloadResult instanceof ReloadGeneration) {
      var casted = (ReloadGeneration) reloadResult;
      // New application classes
      logger.info("🔃 Reloading an application");
      stopInternal();
      startInternal(casted);
      logger.debug("Finished reloading");
      return true;
    } else if (reloadResult == null) {
      // No change in the application classes
      logger.debug("No change in the application classes");
      return false;
    } else if (reloadResult instanceof Throwable) {
      throw new RuntimeException((Throwable) reloadResult);
    }
    return false;
  }

  @Override
  public boolean isRunning() {
    return isRunning.get();
  }

  private void createCurrentGenerationWorker() {
    var ioThreads = Math.max(Runtime.getRuntime().availableProcessors(), 2);
    try {
      this.currentGenerationWorker =
          Xnio.getInstance(getClass().getClassLoader())
              .createWorker(
                  OptionMap.builder()
                      .set(Options.WORKER_IO_THREADS, ioThreads)
                      .set(Options.CONNECTION_HIGH_WATER, 1000000)
                      .set(Options.CONNECTION_LOW_WATER, 1000000)
                      .set(Options.WORKER_TASK_CORE_THREADS, ioThreads * 8)
                      .set(Options.WORKER_TASK_MAX_THREADS, ioThreads * 8)
                      .set(Options.TCP_NODELAY, true)
                      .set(Options.CORK, true)
                      .getMap());
    } catch (Exception e) {
      logger.error("Error during initializing proxy connection worker", e);
    }
  }

  // Shameful copy-n-paste from cask.main.Main.silenceJboss
  private void silenceJboss() {
    // Some jboss classes litter logs from their static initializers. This is a
    // workaround to stop this rather annoying behavior.
    var tmp = System.out;
    System.setOut(null);
    org.jboss.threads.Version.getVersionString(); // this causes the static initializer to be run
    System.setOut(tmp);

    // Other loggers print way too much information. Set them to only print
    // interesting stuff.
    var level = java.util.logging.Level.WARNING;
    java.util.logging.Logger.getLogger("org.jboss").setLevel(level);
    java.util.logging.Logger.getLogger("org.xnio").setLevel(level);
    java.util.logging.Logger.getLogger("io.undertow").setLevel(level);
  }
}
