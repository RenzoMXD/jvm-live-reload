package me.seroperson.reload.live.hook.zio;

import me.seroperson.reload.live.build.BuildLogger
import me.seroperson.reload.live.hook.Hook
import me.seroperson.reload.live.hook.ReflectionUtils
import me.seroperson.reload.live.settings.DevServerSettings

class ZioAppStartupHook extends Hook {

  override def description: String = "Starts a zio.ZIOApp"

  override def isAvailable: Boolean =
    ReflectionUtils.hasClass("zio.ZIOApp$")

  override def hook(
      th: Thread,
      cl: ClassLoader,
      settings: DevServerSettings,
      logger: BuildLogger
  ) = {
    ReflectionUtils.dumpThreads(logger, th.getThreadGroup)

    // We need to update Context ClassLoader on all ZScheduler workers
    // because they usually survive reload
    var matchedCount = ReflectionUtils.updateContextClassLoader(
      th.getThreadGroup,
      v =>
        (if (v == null)
           false
         else {
           var threadName = v.getName
           threadName.startsWith("ZScheduler") || threadName.startsWith(
             "zio"
           ) || threadName.startsWith("globalEventExecutor")
         }),
      cl
    )
    logger.debug(
      s"Set $cl as a context classloader zio computing threads"
    )
  }

}
