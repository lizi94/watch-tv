package com.fongmi.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.util.List;

import android.content.pm.PackageManager.NameNotFoundException;

import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.ComponentInfoFlags;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.Configuration;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.PackageManager.OnChecksumsReadyListener;
import java.security.cert.CertificateEncodingException;
import java.util.Set;
import android.content.pm.ArchivedPackageInfo;
import android.content.pm.InstallSourceInfo;
import android.content.IntentSender;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageManager.Property;
import android.os.Bundle;

/**
 * 转发式 PackageManager 代理：所有方法均转发给系统实现，不做任何签名伪装。
 * 可全局常驻挂载，无 stub 副作用；TVBus 等临时 hook 仍可按需覆盖。
 *
 * 注意：Android 13+ 的 PackageManager 新增了大量带 XxxFlags 参数的具体方法重载
 * （如 getPackageInfo(String, PackageInfoFlags)），基类默认实现直接抛
 * UnsupportedOperationException，因此这些具体方法也必须覆写转发，
 * 否则在新系统设备上调用即崩溃。
 */
public class DelegateHook extends Hook {

    private final PackageManager mDelegate;

    public DelegateHook(String pkgn, PackageManager delegate) {
        super(pkgn);
        this.mDelegate = delegate;
    }

    /**
     * 偷抛 checked 异常：父类 Hook 未声明 throws，覆写方法不能加宽异常声明，
     * 但直接包 RuntimeException 会让只 catch NameNotFoundException 的调用方崩溃。
     * 通过泛型骗过编译器原样抛出，调用方 catch 行为与系统 PackageManager 完全一致。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    @Override
    public PackageInfo getPackageInfo(@NonNull String packageName, int flags) {
        try {
            return mDelegate.getPackageInfo(packageName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.O)
    public PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage, int flags) {
        return getPackageInfo(versionedPackage.getPackageName(), flags);
    }

    @Override
    public String[] currentToCanonicalPackageNames(@NonNull String[] packageNames) {
        return mDelegate.currentToCanonicalPackageNames(packageNames);
    }

    @Override
    public String[] canonicalToCurrentPackageNames(@NonNull String[] packageNames) {
        return mDelegate.canonicalToCurrentPackageNames(packageNames);
    }

    @Override
    public Intent getLaunchIntentForPackage(@NonNull String packageName) {
        return mDelegate.getLaunchIntentForPackage(packageName);
    }

    @Override
    public Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName) {
        return mDelegate.getLeanbackLaunchIntentForPackage(packageName);
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName) {
        try {
            return mDelegate.getPackageGids(packageName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName, int flags) {
        try {
            return mDelegate.getPackageGids(packageName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public int getPackageUid(@NonNull String packageName, int flags) {
        try {
            return mDelegate.getPackageUid(packageName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags) {
        try {
            return mDelegate.getPermissionInfo(permName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String permissionGroup, int flags) {
        try {
            return mDelegate.queryPermissionsByGroup(permissionGroup, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(@NonNull String groupName, int flags) {
        try {
            return mDelegate.getPermissionGroupInfo(groupName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return mDelegate.getAllPermissionGroups(flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(@NonNull String packageName, int flags) {
        try {
            return mDelegate.getApplicationInfo(packageName, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public ActivityInfo getActivityInfo(@NonNull ComponentName component, int flags) {
        try {
            return mDelegate.getActivityInfo(component, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component, int flags) {
        try {
            return mDelegate.getReceiverInfo(component, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public ServiceInfo getServiceInfo(@NonNull ComponentName component, int flags) {
        try {
            return mDelegate.getServiceInfo(component, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component, int flags) {
        try {
            return mDelegate.getProviderInfo(component, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        return mDelegate.getInstalledPackages(flags);
    }

    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions, int flags) {
        return mDelegate.getPackagesHoldingPermissions(permissions, flags);
    }

    @Override
    public int checkPermission(@NonNull String permName, @NonNull String packageName) {
        return mDelegate.checkPermission(permName, packageName);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(@NonNull String permName, @NonNull String packageName) {
        return mDelegate.isPermissionRevokedByPolicy(permName, packageName);
    }

    @Override
    public boolean addPermission(@NonNull PermissionInfo info) {
        return mDelegate.addPermission(info);
    }

    @Override
    public boolean addPermissionAsync(@NonNull PermissionInfo info) {
        return mDelegate.addPermissionAsync(info);
    }

    @Override
    public void removePermission(@NonNull String permName) {
        mDelegate.removePermission(permName);
    }

    @Override
    public int checkSignatures(@NonNull String packageName1, @NonNull String packageName2) {
        return mDelegate.checkSignatures(packageName1, packageName2);
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        return mDelegate.checkSignatures(uid1, uid2);
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        return mDelegate.getPackagesForUid(uid);
    }

    @Override
    public String getNameForUid(int uid) {
        return mDelegate.getNameForUid(uid);
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        return mDelegate.getInstalledApplications(flags);
    }

    @Override
    public boolean isInstantApp() {
        return mDelegate.isInstantApp();
    }

    @Override
    public boolean isInstantApp(@NonNull String packageName) {
        return mDelegate.isInstantApp(packageName);
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return mDelegate.getInstantAppCookieMaxBytes();
    }

    @Override
    public byte[] getInstantAppCookie() {
        return mDelegate.getInstantAppCookie();
    }

    @Override
    public void clearInstantAppCookie() {
        mDelegate.clearInstantAppCookie();
    }

    @Override
    public void updateInstantAppCookie(byte[] cookie) {
        mDelegate.updateInstantAppCookie(cookie);
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        return mDelegate.getSystemSharedLibraryNames();
    }

    @Override
    public List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return mDelegate.getSharedLibraries(flags);
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        return mDelegate.getChangedPackages(sequenceNumber);
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        return mDelegate.getSystemAvailableFeatures();
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName) {
        return mDelegate.hasSystemFeature(featureName);
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName, int version) {
        return mDelegate.hasSystemFeature(featureName, version);
    }

    @Override
    public ResolveInfo resolveActivity(@NonNull Intent intent, int flags) {
        return mDelegate.resolveActivity(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(@NonNull Intent intent, int flags) {
        return mDelegate.queryIntentActivities(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, @NonNull Intent intent, int flags) {
        return mDelegate.queryIntentActivityOptions(caller, specifics, intent, flags);
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent, int flags) {
        return mDelegate.queryBroadcastReceivers(intent, flags);
    }

    @Override
    public ResolveInfo resolveService(@NonNull Intent intent, int flags) {
        return mDelegate.resolveService(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentServices(@NonNull Intent intent, int flags) {
        return mDelegate.queryIntentServices(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        return mDelegate.queryIntentContentProviders(intent, flags);
    }

    @Override
    public ProviderInfo resolveContentProvider(@NonNull String authority, int flags) {
        return mDelegate.resolveContentProvider(authority, flags);
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        return mDelegate.queryContentProviders(processName, uid, flags);
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName className, int flags) {
        try {
            return mDelegate.getInstrumentationInfo(className, flags);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(@NonNull String targetPackage, int flags) {
        return mDelegate.queryInstrumentation(targetPackage, flags);
    }

    @Override
    public Drawable getDrawable(@NonNull String packageName, int resId, ApplicationInfo appInfo) {
        return mDelegate.getDrawable(packageName, resId, appInfo);
    }

    @Override
    public Drawable getActivityIcon(@NonNull ComponentName activityName) {
        try {
            return mDelegate.getActivityIcon(activityName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getActivityIcon(@NonNull Intent intent) {
        try {
            return mDelegate.getActivityIcon(intent);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getActivityBanner(@NonNull ComponentName activityName) {
        try {
            return mDelegate.getActivityBanner(activityName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getActivityBanner(@NonNull Intent intent) {
        try {
            return mDelegate.getActivityBanner(intent);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getDefaultActivityIcon() {
        return mDelegate.getDefaultActivityIcon();
    }

    @Override
    public Drawable getApplicationIcon(@NonNull ApplicationInfo info) {
        return mDelegate.getApplicationIcon(info);
    }

    @Override
    public Drawable getApplicationIcon(@NonNull String packageName) {
        try {
            return mDelegate.getApplicationIcon(packageName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getApplicationBanner(@NonNull ApplicationInfo info) {
        return mDelegate.getApplicationBanner(info);
    }

    @Override
    public Drawable getApplicationBanner(@NonNull String packageName) {
        try {
            return mDelegate.getApplicationBanner(packageName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getActivityLogo(@NonNull ComponentName activityName) {
        try {
            return mDelegate.getActivityLogo(activityName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getActivityLogo(@NonNull Intent intent) {
        try {
            return mDelegate.getActivityLogo(intent);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getApplicationLogo(@NonNull ApplicationInfo info) {
        return mDelegate.getApplicationLogo(info);
    }

    @Override
    public Drawable getApplicationLogo(@NonNull String packageName) {
        try {
            return mDelegate.getApplicationLogo(packageName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Drawable getUserBadgedIcon(@NonNull Drawable drawable, @NonNull UserHandle user) {
        return mDelegate.getUserBadgedIcon(drawable, user);
    }

    @Override
    public Drawable getUserBadgedDrawableForDensity(@NonNull Drawable drawable, @NonNull UserHandle user, Rect badgeLocation, int badgeDensity) {
        return mDelegate.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity);
    }

    @Override
    public CharSequence getUserBadgedLabel(@NonNull CharSequence label, @NonNull UserHandle user) {
        return mDelegate.getUserBadgedLabel(label, user);
    }

    @Override
    public CharSequence getText(@NonNull String packageName, int resId, ApplicationInfo appInfo) {
        return mDelegate.getText(packageName, resId, appInfo);
    }

    @Override
    public XmlResourceParser getXml(@NonNull String packageName, int resId, ApplicationInfo appInfo) {
        return mDelegate.getXml(packageName, resId, appInfo);
    }

    @Override
    public CharSequence getApplicationLabel(@NonNull ApplicationInfo info) {
        return mDelegate.getApplicationLabel(info);
    }

    @Override
    public Resources getResourcesForActivity(@NonNull ComponentName activityName) {
        try {
            return mDelegate.getResourcesForActivity(activityName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Resources getResourcesForApplication(@NonNull ApplicationInfo app) {
        try {
            return mDelegate.getResourcesForApplication(app);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Resources getResourcesForApplication(@NonNull String packageName) {
        try {
            return mDelegate.getResourcesForApplication(packageName);
        } catch (NameNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) {
        mDelegate.verifyPendingInstall(id, verificationCode);
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
        mDelegate.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
    }

    @Override
    public void setInstallerPackageName(@NonNull String targetPackage, String installerPackageName) {
        mDelegate.setInstallerPackageName(targetPackage, installerPackageName);
    }

    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        return mDelegate.getInstallerPackageName(packageName);
    }

    @Override
    public void addPackageToPreferred(@NonNull String packageName) {
        mDelegate.addPackageToPreferred(packageName);
    }

    @Override
    public void removePackageFromPreferred(@NonNull String packageName) {
        mDelegate.removePackageFromPreferred(packageName);
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return mDelegate.getPreferredPackages(flags);
    }

    @Override
    public void addPreferredActivity(@NonNull IntentFilter filter, int match, ComponentName[] set, @NonNull ComponentName activity) {
        mDelegate.addPreferredActivity(filter, match, set, activity);
    }

    @Override
    public void clearPackagePreferredActivities(@NonNull String packageName) {
        mDelegate.clearPackagePreferredActivities(packageName);
    }

    @Override
    public int getPreferredActivities(@NonNull List<IntentFilter> outFilters, @NonNull List<ComponentName> outActivities, String packageName) {
        return mDelegate.getPreferredActivities(outFilters, outActivities, packageName);
    }

    @Override
    public void setComponentEnabledSetting(@NonNull ComponentName componentName, int newState, int flags) {
        mDelegate.setComponentEnabledSetting(componentName, newState, flags);
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName componentName) {
        return mDelegate.getComponentEnabledSetting(componentName);
    }

    @Override
    public void setApplicationEnabledSetting(@NonNull String packageName, int newState, int flags) {
        mDelegate.setApplicationEnabledSetting(packageName, newState, flags);
    }

    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName) {
        return mDelegate.getApplicationEnabledSetting(packageName);
    }

    @Override
    public boolean isSafeMode() {
        return mDelegate.isSafeMode();
    }

    @Override
    public void setApplicationCategoryHint(@NonNull String packageName, int categoryHint) {
        mDelegate.setApplicationCategoryHint(packageName, categoryHint);
    }

    @Override
    public PackageInstaller getPackageInstaller() {
        return mDelegate.getPackageInstaller();
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return mDelegate.canRequestPackageInstalls();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean addWhitelistedRestrictedPermission(String arg0, String arg1, int arg2) {
        return mDelegate.addWhitelistedRestrictedPermission(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean[] canPackageQuery(String arg0, String[] arg1) throws NameNotFoundException {
        return mDelegate.canPackageQuery(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean canPackageQuery(String arg0, String arg1) throws NameNotFoundException {
        return mDelegate.canPackageQuery(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ActivityInfo getActivityInfo(ComponentName arg0, ComponentInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getActivityInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ApplicationInfo getApplicationInfo(String arg0, ApplicationInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getApplicationInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public int getAppUidForPrivateComputeCoreUid(int arg0) {
        return mDelegate.getAppUidForPrivateComputeCoreUid(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ArchivedPackageInfo getArchivedPackage(String arg0) {
        return mDelegate.getArchivedPackage(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public CharSequence getBackgroundPermissionOptionLabel() {
        return mDelegate.getBackgroundPermissionOptionLabel();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void getGroupOfPlatformPermission(String arg0, Executor arg1, Consumer arg2) {
        mDelegate.getGroupOfPlatformPermission(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List getInstalledApplications(ApplicationInfoFlags arg0) {
        return mDelegate.getInstalledApplications(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List getInstalledModules(int arg0) {
        return mDelegate.getInstalledModules(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List getInstalledPackages(PackageInfoFlags arg0) {
        return mDelegate.getInstalledPackages(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public InstallSourceInfo getInstallSourceInfo(String arg0) throws NameNotFoundException {
        return mDelegate.getInstallSourceInfo(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public IntentSender getLaunchIntentSenderForPackage(String arg0) {
        return mDelegate.getLaunchIntentSenderForPackage(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Set getMimeGroup(String arg0) {
        return mDelegate.getMimeGroup(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ModuleInfo getModuleInfo(String arg0, int arg1) throws NameNotFoundException {
        return mDelegate.getModuleInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageArchiveInfo(String arg0, int arg1) {
        return mDelegate.getPackageArchiveInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageArchiveInfo(String arg0, PackageInfoFlags arg1) {
        return mDelegate.getPackageArchiveInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public int[] getPackageGids(String arg0, PackageInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getPackageGids(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageInfo(String arg0, PackageManager.PackageInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getPackageInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageInfo(VersionedPackage arg0, PackageInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getPackageInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List getPackagesHoldingPermissions(String[] arg0, PackageInfoFlags arg1) {
        return mDelegate.getPackagesHoldingPermissions(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public int getPackageUid(String arg0, PackageInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getPackageUid(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void getPlatformPermissionsForGroup(String arg0, Executor arg1, Consumer arg2) {
        mDelegate.getPlatformPermissionsForGroup(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Property getProperty(String arg0, String arg1) throws NameNotFoundException {
        return mDelegate.getProperty(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Property getProperty(String arg0, ComponentName arg1) throws NameNotFoundException {
        return mDelegate.getProperty(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ProviderInfo getProviderInfo(ComponentName arg0, ComponentInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getProviderInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ActivityInfo getReceiverInfo(ComponentName arg0, ComponentInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getReceiverInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Resources getResourcesForApplication(ApplicationInfo arg0, Configuration arg1) throws NameNotFoundException {
        return mDelegate.getResourcesForApplication(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ServiceInfo getServiceInfo(ComponentName arg0, ComponentInfoFlags arg1) throws NameNotFoundException {
        return mDelegate.getServiceInfo(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List getSharedLibraries(PackageInfoFlags arg0) {
        return mDelegate.getSharedLibraries(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Bundle getSuspendedPackageAppExtras() {
        return mDelegate.getSuspendedPackageAppExtras();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean getSyntheticAppDetailsActivityEnabled(String arg0) {
        return mDelegate.getSyntheticAppDetailsActivityEnabled(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public int getTargetSdkVersion(String arg0) throws NameNotFoundException {
        return mDelegate.getTargetSdkVersion(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public Set getWhitelistedRestrictedPermissions(String arg0, int arg1) {
        return mDelegate.getWhitelistedRestrictedPermissions(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean hasSigningCertificate(String arg0, byte[] arg1, int arg2) {
        return mDelegate.hasSigningCertificate(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean hasSigningCertificate(int arg0, byte[] arg1, int arg2) {
        return mDelegate.hasSigningCertificate(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isAppArchivable(String arg0) throws NameNotFoundException {
        return mDelegate.isAppArchivable(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isAutoRevokeWhitelisted(String arg0) {
        return mDelegate.isAutoRevokeWhitelisted(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isAutoRevokeWhitelisted() {
        return mDelegate.isAutoRevokeWhitelisted();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isDefaultApplicationIcon(Drawable arg0) {
        return mDelegate.isDefaultApplicationIcon(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isDeviceUpgrading() {
        return mDelegate.isDeviceUpgrading();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isPackageStopped(String arg0) throws NameNotFoundException {
        return mDelegate.isPackageStopped(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isPackageSuspended(String arg0) throws NameNotFoundException {
        return mDelegate.isPackageSuspended(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean isPackageSuspended() {
        return mDelegate.isPackageSuspended();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryActivityProperty(String arg0) {
        return mDelegate.queryActivityProperty(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryApplicationProperty(String arg0) {
        return mDelegate.queryApplicationProperty(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryBroadcastReceivers(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.queryBroadcastReceivers(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryContentProviders(String arg0, int arg1, ComponentInfoFlags arg2) {
        return mDelegate.queryContentProviders(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryIntentActivities(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.queryIntentActivities(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryIntentActivityOptions(ComponentName arg0, List arg1, Intent arg2, ResolveInfoFlags arg3) {
        return mDelegate.queryIntentActivityOptions(arg0, arg1, arg2, arg3);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryIntentContentProviders(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.queryIntentContentProviders(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryIntentServices(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.queryIntentServices(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryProviderProperty(String arg0) {
        return mDelegate.queryProviderProperty(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryReceiverProperty(String arg0) {
        return mDelegate.queryReceiverProperty(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public List queryServiceProperty(String arg0) {
        return mDelegate.queryServiceProperty(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void relinquishUpdateOwnership(String arg0) {
        mDelegate.relinquishUpdateOwnership(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean removeWhitelistedRestrictedPermission(String arg0, String arg1, int arg2) {
        return mDelegate.removeWhitelistedRestrictedPermission(arg0, arg1, arg2);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void requestChecksums(String arg0, boolean arg1, int arg2, List arg3, OnChecksumsReadyListener arg4) throws CertificateEncodingException, NameNotFoundException {
        mDelegate.requestChecksums(arg0, arg1, arg2, arg3, arg4);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ResolveInfo resolveActivity(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.resolveActivity(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ProviderInfo resolveContentProvider(String arg0, ComponentInfoFlags arg1) {
        return mDelegate.resolveContentProvider(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public ResolveInfo resolveService(Intent arg0, ResolveInfoFlags arg1) {
        return mDelegate.resolveService(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public boolean setAutoRevokeWhitelisted(String arg0, boolean arg1) {
        return mDelegate.setAutoRevokeWhitelisted(arg0, arg1);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void setComponentEnabledSettings(List arg0) {
        mDelegate.setComponentEnabledSettings(arg0);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void setMimeGroup(String arg0, Set arg1) {
        mDelegate.setMimeGroup(arg0, arg1);
    }

}
