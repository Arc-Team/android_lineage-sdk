package org.lineageos.internal.util;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.widget.Toast;

import org.lineageos.platform.internal.R;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ActionUtils {
    private static final boolean DEBUG = false;
    private static final String TAG = ActionUtils.class.getSimpleName();
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return {@code true} when a user application was found and closed.
     */
    public static boolean killForegroundApp(Context context, int userId) {
        return killForegroundAppInternal(context);
    }

    private static boolean killForegroundAppInternal(Context context) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.FORCE_STOP_PACKAGES) == PackageManager.PERMISSION_GRANTED) {
            try {
                PackageManager packageManager = context.getPackageManager();
                final Intent intent = new Intent(Intent.ACTION_MAIN);
                String defaultHomePackage = "com.android.launcher";
                intent.addCategory(Intent.CATEGORY_HOME);
                final ResolveInfo res = packageManager.resolveActivity(intent, 0);
                if (res.activityInfo != null
                        && !res.activityInfo.packageName.equals("android")) {
                    defaultHomePackage = res.activityInfo.packageName;
                }

                // Use UsageStats to determine foreground app
                UsageStatsManager usageStatsManager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
                long current = System.currentTimeMillis();
                long past = current - (1000 * 60 * 60); // uses snapshot of usage over past 60 minutes

                // Get the list, then sort it chronilogically so most recent usage is at start of list
                List<UsageStats> recentApps = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, past, current);
                Collections.sort(recentApps, new Comparator<UsageStats>() {
                    @Override
                    public int compare(UsageStats lhs, UsageStats rhs) {
                        long timeLHS = lhs.getLastTimeUsed();
                        long timeRHS = rhs.getLastTimeUsed();
                        if (timeLHS > timeRHS) {
                            return -1;
                        } else if (timeLHS < timeRHS) {
                            return 1;
                        }
                        return 0;
                    }
                });

                IActivityManager iam = ActivityManagerNative.getDefault();
                // this may not be needed due to !isLockTaskOn() in entry if
                //if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;

                // Look for most recent usagestat with lastevent == 1 and grab package name
                // ...this seems to map to the UsageEvents.Event.MOVE_TO_FOREGROUND
                String pkg = null;
                for (int i = 0; i < recentApps.size(); i++) {
                    UsageStats mostRecent = recentApps.get(i);
                    if (mostRecent.mLastEvent == 1) {
                        pkg = mostRecent.mPackageName;
                        break;
                    }
                }

                if (pkg != null && !pkg.equals("com.android.systemui")
                        && !pkg.equals(defaultHomePackage)) {

                    // Restore home screen stack before killing the app
                    Intent home = new Intent(Intent.ACTION_MAIN, null);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(home);

                    // Kill the app
                    iam.forceStopPackage(pkg, UserHandle.USER_CURRENT);

                    // Remove killed app from Recents
                    final ActivityManager am = (ActivityManager)
                            context.getSystemService(Context.ACTIVITY_SERVICE);
                    final List<ActivityManager.RecentTaskInfo> recentTasks =
                            am.getRecentTasksForUser(ActivityManager.getMaxRecentTasksStatic(),
                            ActivityManager.RECENT_IGNORE_HOME_AND_RECENTS_STACK_TASKS
                                    | ActivityManager.RECENT_INGORE_PINNED_STACK_TASKS
                                    | ActivityManager.RECENT_IGNORE_UNAVAILABLE
                                    | ActivityManager.RECENT_INCLUDE_PROFILES,
                                    UserHandle.CURRENT.getIdentifier());
                    final int size = recentTasks.size();
                    for (int i = 0; i < size; i++) {
                        ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);
                        if (recentInfo.baseIntent.getComponent().getPackageName().equals(pkg)) {
                            int taskid = recentInfo.persistentId;
                            am.removeTask(taskid);
                        }
                    }
                    return true;
                } else {
                    return false;
                }
            } catch (RemoteException remoteException) {
                Log.d(TAG, "Caller cannot kill processes, aborting");
            }
        } else {
            Log.d(TAG, "Caller cannot kill processes, aborting");
        }
        return false;
    }

    /**
     * Attempt to bring up the last activity in the stack before the current active one.
     *
     * @param context
     * @return whether an activity was found to switch to
     */
    public static boolean switchToLastApp(Context context, int userId) {
        try {
            return switchToLastAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not switch to last app");
        }
        return false;
    }

    private static boolean switchToLastAppInternal(Context context, int userId)
            throws RemoteException {
        ActivityManager.RecentTaskInfo lastTask = getLastTask(context, userId);

        if (lastTask == null || lastTask.id < 0) {
            return false;
        }

        final String packageName = lastTask.baseIntent.getComponent().getPackageName();
        final IActivityManager am = ActivityManagerNative.getDefault();
        final ActivityOptions opts = ActivityOptions.makeCustomAnimation(context,
                org.lineageos.platform.internal.R.anim.last_app_in,
                org.lineageos.platform.internal.R.anim.last_app_out);

        if (DEBUG) Log.d(TAG, "switching to " + packageName);
        am.moveTaskToFront(lastTask.id, ActivityManager.MOVE_TASK_NO_USER_ACTION, opts.toBundle());

        return true;
    }

    private static ActivityManager.RecentTaskInfo getLastTask(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasksForUser(5,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId);

        for (int i = 1; i < tasks.size(); i++) {
            ActivityManager.RecentTaskInfo task = tasks.get(i);
            if (task.origActivity != null) {
                task.baseIntent.setComponent(task.origActivity);
            }
            String packageName = task.baseIntent.getComponent().getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(SYSTEMUI_PACKAGE)) {
                return tasks.get(i);
            }
        }

        return null;
    }

    private static String resolveCurrentLauncherPackage(Context context, int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(launcherIntent, 0, userId);
        return launcherInfo.activityInfo.packageName;
    }
}
