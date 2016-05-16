/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.annotation.TargetApi;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserManager;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.launcher3.AutoInstallsLayout.LayoutParserCallback;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.LauncherSettings.WorkspaceScreens;
import com.android.launcher3.compat.UserHandleCompat;
import com.android.launcher3.compat.UserManagerCompat;
import com.android.launcher3.config.ProviderConfig;
import com.android.launcher3.dynamicui.ExtractionUtils;
import com.android.launcher3.util.ManagedProfileHeuristic;
import com.android.launcher3.util.NoLocaleSqliteContext;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.Thunk;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class LauncherProvider extends ContentProvider {
    private static final String TAG = "LauncherProvider";
    private static final boolean LOGD = false;

    private static final int DATABASE_VERSION = 26;

    public static final String AUTHORITY = ProviderConfig.AUTHORITY;

    static final String EMPTY_DATABASE_CREATED = "EMPTY_DATABASE_CREATED";

    private static final String RESTRICTION_PACKAGE_NAME = "workspace.configuration.package.name";

    private final ChangeListenerWrapper mListenerWrapper = new ChangeListenerWrapper();
    private Handler mListenerHandler;

    protected DatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
        mListenerHandler = new Handler(mListenerWrapper);

        LauncherAppState.setLauncherProvider(this);
        return true;
    }

    /**
     * Sets a provider listener.
     */
    public void setLauncherProviderChangeListener(LauncherProviderChangeListener listener) {
        Preconditions.assertUIThread();
        mListenerWrapper.mListener = listener;
    }

    @Override
    public String getType(Uri uri) {
        SqlArguments args = new SqlArguments(uri, null, null);
        if (TextUtils.isEmpty(args.where)) {
            return "vnd.android.cursor.dir/" + args.table;
        } else {
            return "vnd.android.cursor.item/" + args.table;
        }
    }

    /**
     * Overridden in tests
     */
    protected synchronized void createDbIfNotExists() {
        if (mOpenHelper == null) {
            mOpenHelper = new DatabaseHelper(getContext(), mListenerHandler);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        createDbIfNotExists();

        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(args.table);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor result = qb.query(db, projection, args.where, args.args, null, null, sortOrder);
        result.setNotificationUri(getContext().getContentResolver(), uri);

        return result;
    }

    @Thunk static long dbInsertAndCheck(DatabaseHelper helper,
            SQLiteDatabase db, String table, String nullColumnHack, ContentValues values) {
        if (values == null) {
            throw new RuntimeException("Error: attempting to insert null values");
        }
        if (!values.containsKey(LauncherSettings.ChangeLogColumns._ID)) {
            throw new RuntimeException("Error: attempting to add item without specifying an id");
        }
        helper.checkId(table, values);
        return db.insert(table, nullColumnHack, values);
    }

    private void reloadLauncherIfExternal() {
        if (Utilities.ATLEAST_MARSHMALLOW && Binder.getCallingPid() != Process.myPid()) {
            LauncherAppState app = LauncherAppState.getInstanceNoCreate();
            if (app != null) {
                app.reloadWorkspace();
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri);

        // In very limited cases, we support system|signature permission apps to modify the db.
        if (Binder.getCallingPid() != Process.myPid()) {
            if (!initializeExternalAdd(initialValues)) {
                return null;
            }
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        addModifiedTime(initialValues);
        final long rowId = dbInsertAndCheck(mOpenHelper, db, args.table, null, initialValues);
        if (rowId < 0) return null;

        uri = ContentUris.withAppendedId(uri, rowId);
        notifyListeners();

        if (Utilities.ATLEAST_MARSHMALLOW) {
            reloadLauncherIfExternal();
        } else {
            // Deprecated behavior to support legacy devices which rely on provider callbacks.
            LauncherAppState app = LauncherAppState.getInstanceNoCreate();
            if (app != null && "true".equals(uri.getQueryParameter("isExternalAdd"))) {
                app.reloadWorkspace();
            }

            String notify = uri.getQueryParameter("notify");
            if (notify == null || "true".equals(notify)) {
                getContext().getContentResolver().notifyChange(uri, null);
            }
        }
        return uri;
    }

    private boolean initializeExternalAdd(ContentValues values) {
        // 1. Ensure that externally added items have a valid item id
        long id = mOpenHelper.generateNewItemId();
        values.put(LauncherSettings.Favorites._ID, id);

        // 2. In the case of an app widget, and if no app widget id is specified, we
        // attempt allocate and bind the widget.
        Integer itemType = values.getAsInteger(LauncherSettings.Favorites.ITEM_TYPE);
        if (itemType != null &&
                itemType.intValue() == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET &&
                !values.containsKey(LauncherSettings.Favorites.APPWIDGET_ID)) {

            final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(getContext());
            ComponentName cn = ComponentName.unflattenFromString(
                    values.getAsString(Favorites.APPWIDGET_PROVIDER));

            if (cn != null) {
                try {
                    int appWidgetId = new AppWidgetHost(getContext(), Launcher.APPWIDGET_HOST_ID)
                            .allocateAppWidgetId();
                    values.put(LauncherSettings.Favorites.APPWIDGET_ID, appWidgetId);
                    if (!appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId,cn)) {
                        return false;
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to initialize external widget", e);
                    return false;
                }
            } else {
                return false;
            }
        }

        // Add screen id if not present
        long screenId = values.getAsLong(LauncherSettings.Favorites.SCREEN);
        SQLiteStatement stmp = null;
        try {
            stmp = mOpenHelper.getWritableDatabase().compileStatement(
                    "INSERT OR IGNORE INTO workspaceScreens (_id, screenRank) " +
                            "select ?, (ifnull(MAX(screenRank), -1)+1) from workspaceScreens");
            stmp.bindLong(1, screenId);

            ContentValues valuesInserted = new ContentValues();
            valuesInserted.put(LauncherSettings.BaseLauncherColumns._ID, stmp.executeInsert());
            mOpenHelper.checkId(WorkspaceScreens.TABLE_NAME, valuesInserted);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Utilities.closeSilently(stmp);
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int numValues = values.length;
            for (int i = 0; i < numValues; i++) {
                addModifiedTime(values[i]);
                if (dbInsertAndCheck(mOpenHelper, db, args.table, null, values[i]) < 0) {
                    return 0;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        notifyListeners();
        reloadLauncherIfExternal();
        return values.length;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        createDbIfNotExists();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentProviderResult[] result =  super.applyBatch(operations);
            db.setTransactionSuccessful();
            reloadLauncherIfExternal();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.delete(args.table, args.where, args.args);
        if (count > 0) notifyListeners();

        reloadLauncherIfExternal();
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);

        addModifiedTime(values);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(args.table, values, args.where, args.args);
        if (count > 0) notifyListeners();

        reloadLauncherIfExternal();
        return count;
    }

    @Override
    public Bundle call(String method, final String arg, final Bundle extras) {
        if (Binder.getCallingUid() != Process.myUid()) {
            return null;
        }
        createDbIfNotExists();

        switch (method) {
            case LauncherSettings.Settings.METHOD_SET_EXTRACTED_COLORS_AND_WALLPAPER_ID: {
                String extractedColors = extras.getString(
                        LauncherSettings.Settings.EXTRA_EXTRACTED_COLORS);
                int wallpaperId = extras.getInt(LauncherSettings.Settings.EXTRA_WALLPAPER_ID);
                Utilities.getPrefs(getContext()).edit()
                        .putString(ExtractionUtils.EXTRACTED_COLORS_PREFERENCE_KEY, extractedColors)
                        .putInt(ExtractionUtils.WALLPAPER_ID_PREFERENCE_KEY, wallpaperId)
                        .apply();
                mListenerHandler.sendEmptyMessage(ChangeListenerWrapper.MSG_EXTRACTED_COLORS_CHANGED);
                Bundle result = new Bundle();
                result.putString(LauncherSettings.Settings.EXTRA_VALUE, extractedColors);
                return result;
            }
            case LauncherSettings.Settings.METHOD_CLEAR_EMPTY_DB_FLAG: {
                clearFlagEmptyDbCreated();
                return null;
            }
            case LauncherSettings.Settings.METHOD_DELETE_EMPTY_FOLDERS: {
                Bundle result = new Bundle();
                result.putSerializable(LauncherSettings.Settings.EXTRA_VALUE, deleteEmptyFolders());
                return result;
            }
            case LauncherSettings.Settings.METHOD_NEW_ITEM_ID: {
                Bundle result = new Bundle();
                result.putLong(LauncherSettings.Settings.EXTRA_VALUE, mOpenHelper.generateNewItemId());
                return result;
            }
            case LauncherSettings.Settings.METHOD_NEW_SCREEN_ID: {
                Bundle result = new Bundle();
                result.putLong(LauncherSettings.Settings.EXTRA_VALUE, mOpenHelper.generateNewScreenId());
                return result;
            }
            case LauncherSettings.Settings.METHOD_CREATE_EMPTY_DB: {
                createEmptyDB();
                return null;
            }
            case LauncherSettings.Settings.METHOD_LOAD_DEFAULT_FAVORITES: {
                loadDefaultFavoritesIfNecessary();
                return null;
            }
            case LauncherSettings.Settings.METHOD_MIGRATE_LAUNCHER2_SHORTCUTS: {
                mOpenHelper.migrateLauncher2Shortcuts(mOpenHelper.getWritableDatabase(),
                        Uri.parse(getContext().getString(R.string.old_launcher_provider_uri)));
                Utilities.getPrefs(getContext()).edit().putBoolean(EMPTY_DATABASE_CREATED, false)
                        .commit();
                return null;
            }
            case LauncherSettings.Settings.METHOD_UPDATE_FOLDER_ITEMS_RANK: {
                mOpenHelper.updateFolderItemsRank(mOpenHelper.getWritableDatabase(), false);
                return null;
            }
            case LauncherSettings.Settings.METHOD_CONVERT_SHORTCUTS_TO_ACTIVITIES: {
                mOpenHelper.convertShortcutsToLauncherActivities(mOpenHelper.getWritableDatabase());
                return null;
            }
            case LauncherSettings.Settings.METHOD_DELETE_DB: {
                // Are you sure? (y/n)
                mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                return null;
            }
        }
        return null;
    }

    /**
     * Deletes any empty folder from the DB.
     * @return Ids of deleted folders.
     */
    private ArrayList<Long> deleteEmptyFolders() {
        ArrayList<Long> folderIds = new ArrayList<>();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            // Select folders whose id do not match any container value.
            String selection = LauncherSettings.Favorites.ITEM_TYPE + " = "
                    + LauncherSettings.Favorites.ITEM_TYPE_FOLDER + " AND "
                    + LauncherSettings.Favorites._ID +  " NOT IN (SELECT " +
                            LauncherSettings.Favorites.CONTAINER + " FROM "
                                + Favorites.TABLE_NAME + ")";
            Cursor c = db.query(Favorites.TABLE_NAME,
                    new String[] {LauncherSettings.Favorites._ID},
                    selection, null, null, null, null);
            while (c.moveToNext()) {
                folderIds.add(c.getLong(0));
            }
            c.close();
            if (!folderIds.isEmpty()) {
                db.delete(Favorites.TABLE_NAME, Utilities.createDbSelectionQuery(
                        LauncherSettings.Favorites._ID, folderIds), null);
            }
            db.setTransactionSuccessful();
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            folderIds.clear();
        } finally {
            db.endTransaction();
        }
        return folderIds;
    }

    /**
     * Overridden in tests
     */
    protected void notifyListeners() {
        // always notify the backup agent
        LauncherBackupAgentHelper.dataChanged(getContext());
        mListenerHandler.sendEmptyMessage(ChangeListenerWrapper.MSG_LAUNCHER_PROVIDER_CHANGED);
    }

    @Thunk static void addModifiedTime(ContentValues values) {
        values.put(LauncherSettings.ChangeLogColumns.MODIFIED, System.currentTimeMillis());
    }

    /**
     * Clears all the data for a fresh start.
     */
    synchronized private void createEmptyDB() {
        mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
    }

    private void clearFlagEmptyDbCreated() {
        Utilities.getPrefs(getContext()).edit().remove(EMPTY_DATABASE_CREATED).commit();
    }

    /**
     * Loads the default workspace based on the following priority scheme:
     *   1) From the app restrictions
     *   2) From a package provided by play store
     *   3) From a partner configuration APK, already in the system image
     *   4) The default configuration for the particular device
     */
    synchronized private void loadDefaultFavoritesIfNecessary() {
        SharedPreferences sp = Utilities.getPrefs(getContext());

        if (sp.getBoolean(EMPTY_DATABASE_CREATED, false)) {
            Log.d(TAG, "loading default workspace");

            AppWidgetHost widgetHost = new AppWidgetHost(getContext(), Launcher.APPWIDGET_HOST_ID);
            AutoInstallsLayout loader = createWorkspaceLoaderFromAppRestriction(widgetHost);
            if (loader == null) {
                loader = AutoInstallsLayout.get(getContext(),widgetHost, mOpenHelper);
            }
            if (loader == null) {
                final Partner partner = Partner.get(getContext().getPackageManager());
                if (partner != null && partner.hasDefaultLayout()) {
                    final Resources partnerRes = partner.getResources();
                    int workspaceResId = partnerRes.getIdentifier(Partner.RES_DEFAULT_LAYOUT,
                            "xml", partner.getPackageName());
                    if (workspaceResId != 0) {
                        loader = new DefaultLayoutParser(getContext(), widgetHost,
                                mOpenHelper, partnerRes, workspaceResId);
                    }
                }
            }

            final boolean usingExternallyProvidedLayout = loader != null;
            if (loader == null) {
                loader = getDefaultLayoutParser(widgetHost);
            }

            // There might be some partially restored DB items, due to buggy restore logic in
            // previous versions of launcher.
            createEmptyDB();
            // Populate favorites table with initial favorites
            if ((mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(), loader) <= 0)
                    && usingExternallyProvidedLayout) {
                // Unable to load external layout. Cleanup and load the internal layout.
                createEmptyDB();
                mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(),
                        getDefaultLayoutParser(widgetHost));
            }
            clearFlagEmptyDbCreated();
        }
    }

    /**
     * Creates workspace loader from an XML resource listed in the app restrictions.
     *
     * @return the loader if the restrictions are set and the resource exists; null otherwise.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private AutoInstallsLayout createWorkspaceLoaderFromAppRestriction(AppWidgetHost widgetHost) {
        // UserManager.getApplicationRestrictions() requires minSdkVersion >= 18
        if (!Utilities.ATLEAST_JB_MR2) {
            return null;
        }

        Context ctx = getContext();
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        Bundle bundle = um.getApplicationRestrictions(ctx.getPackageName());
        if (bundle == null) {
            return null;
        }

        String packageName = bundle.getString(RESTRICTION_PACKAGE_NAME);
        if (packageName != null) {
            try {
                Resources targetResources = ctx.getPackageManager()
                        .getResourcesForApplication(packageName);
                return AutoInstallsLayout.get(ctx, packageName, targetResources,
                        widgetHost, mOpenHelper);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Target package for restricted profile not found", e);
                return null;
            }
        }
        return null;
    }

    private DefaultLayoutParser getDefaultLayoutParser(AppWidgetHost widgetHost) {
        int defaultLayout = LauncherAppState.getInstance()
                .getInvariantDeviceProfile().defaultLayoutId;
        return new DefaultLayoutParser(getContext(), widgetHost,
                mOpenHelper, getContext().getResources(), defaultLayout);
    }

    /**
     * The class is subclassed in tests to create an in-memory db.
     */
    public static class DatabaseHelper extends SQLiteOpenHelper implements LayoutParserCallback {
        private final Handler mWidgetHostResetHandler;
        private final Context mContext;
        private long mMaxItemId = -1;
        private long mMaxScreenId = -1;

        DatabaseHelper(Context context, Handler widgetHostResetHandler) {
            this(context, widgetHostResetHandler, LauncherFiles.LAUNCHER_DB);
            // Table creation sometimes fails silently, which leads to a crash loop.
            // This way, we will try to create a table every time after crash, so the device
            // would eventually be able to recover.
            if (!tableExists(Favorites.TABLE_NAME) || !tableExists(WorkspaceScreens.TABLE_NAME)) {
                Log.e(TAG, "Tables are missing after onCreate has been called. Trying to recreate");
                // This operation is a no-op if the table already exists.
                addFavoritesTable(getWritableDatabase(), true);
                addWorkspacesTable(getWritableDatabase(), true);
            }

            initIds();
        }

        /**
         * Constructor used in tests and for restore.
         */
        public DatabaseHelper(
                Context context, Handler widgetHostResetHandler, String tableName) {
            super(new NoLocaleSqliteContext(context), tableName, null, DATABASE_VERSION);
            mContext = context;
            mWidgetHostResetHandler = widgetHostResetHandler;
        }

        protected void initIds() {
            // In the case where neither onCreate nor onUpgrade gets called, we read the maxId from
            // the DB here
            if (mMaxItemId == -1) {
                mMaxItemId = initializeMaxItemId(getWritableDatabase());
            }
            if (mMaxScreenId == -1) {
                mMaxScreenId = initializeMaxScreenId(getWritableDatabase());
            }
        }

        private boolean tableExists(String tableName) {
            Cursor c = getReadableDatabase().query(
                    true, "sqlite_master", new String[] {"tbl_name"},
                    "tbl_name = ?", new String[] {tableName},
                    null, null, null, null, null);
            try {
                return c.getCount() > 0;
            } finally {
                c.close();
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (LOGD) Log.d(TAG, "creating new launcher database");

            mMaxItemId = 1;
            mMaxScreenId = 0;

            addFavoritesTable(db, false);
            addWorkspacesTable(db, false);

            // Fresh and clean launcher DB.
            mMaxItemId = initializeMaxItemId(db);
            onEmptyDbCreated();
        }

        /**
         * Overriden in tests.
         */
        protected void onEmptyDbCreated() {
            // Database was just created, so wipe any previous widgets
            if (mWidgetHostResetHandler != null) {
                new AppWidgetHost(mContext, Launcher.APPWIDGET_HOST_ID).deleteHost();
                mWidgetHostResetHandler.sendEmptyMessage(
                        ChangeListenerWrapper.MSG_APP_WIDGET_HOST_RESET);
            }

            // Set the flag for empty DB
            Utilities.getPrefs(mContext).edit().putBoolean(EMPTY_DATABASE_CREATED, true).commit();

            // When a new DB is created, remove all previously stored managed profile information.
            ManagedProfileHeuristic.processAllUsers(Collections.<UserHandleCompat>emptyList(),
                    mContext);
        }

        protected long getDefaultUserSerial() {
            return UserManagerCompat.getInstance(mContext).getSerialNumberForUser(
                    UserHandleCompat.myUserHandle());
        }

        private void addFavoritesTable(SQLiteDatabase db, boolean optional) {
            Favorites.addTableToDb(db, getDefaultUserSerial(), optional);
        }

        private void addWorkspacesTable(SQLiteDatabase db, boolean optional) {
            String ifNotExists = optional ? " IF NOT EXISTS " : "";
            db.execSQL("CREATE TABLE " + ifNotExists + WorkspaceScreens.TABLE_NAME + " (" +
                    LauncherSettings.WorkspaceScreens._ID + " INTEGER PRIMARY KEY," +
                    LauncherSettings.WorkspaceScreens.SCREEN_RANK + " INTEGER," +
                    LauncherSettings.ChangeLogColumns.MODIFIED + " INTEGER NOT NULL DEFAULT 0" +
                    ");");
        }

        private void removeOrphanedItems(SQLiteDatabase db) {
            // Delete items directly on the workspace who's screen id doesn't exist
            //  "DELETE FROM favorites WHERE screen NOT IN (SELECT _id FROM workspaceScreens)
            //   AND container = -100"
            String removeOrphanedDesktopItems = "DELETE FROM " + Favorites.TABLE_NAME +
                    " WHERE " +
                    LauncherSettings.Favorites.SCREEN + " NOT IN (SELECT " +
                    LauncherSettings.WorkspaceScreens._ID + " FROM " + WorkspaceScreens.TABLE_NAME + ")" +
                    " AND " +
                    LauncherSettings.Favorites.CONTAINER + " = " +
                    LauncherSettings.Favorites.CONTAINER_DESKTOP;
            db.execSQL(removeOrphanedDesktopItems);

            // Delete items contained in folders which no longer exist (after above statement)
            //  "DELETE FROM favorites  WHERE container <> -100 AND container <> -101 AND container
            //   NOT IN (SELECT _id FROM favorites WHERE itemType = 2)"
            String removeOrphanedFolderItems = "DELETE FROM " + Favorites.TABLE_NAME +
                    " WHERE " +
                    LauncherSettings.Favorites.CONTAINER + " <> " +
                    LauncherSettings.Favorites.CONTAINER_DESKTOP +
                    " AND "
                    + LauncherSettings.Favorites.CONTAINER + " <> " +
                    LauncherSettings.Favorites.CONTAINER_HOTSEAT +
                    " AND "
                    + LauncherSettings.Favorites.CONTAINER + " NOT IN (SELECT " +
                    LauncherSettings.Favorites._ID + " FROM " + Favorites.TABLE_NAME +
                    " WHERE " + LauncherSettings.Favorites.ITEM_TYPE + " = " +
                    LauncherSettings.Favorites.ITEM_TYPE_FOLDER + ")";
            db.execSQL(removeOrphanedFolderItems);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (LOGD) Log.d(TAG, "onUpgrade triggered: " + oldVersion);
            switch (oldVersion) {
                // The version cannot be lower that 12, as Launcher3 never supported a lower
                // version of the DB.
                case 12: {
                    // With the new shrink-wrapped and re-orderable workspaces, it makes sense
                    // to persist workspace screens and their relative order.
                    mMaxScreenId = 0;
                    addWorkspacesTable(db, false);
                }
                case 13: {
                    db.beginTransaction();
                    try {
                        // Insert new column for holding widget provider name
                        db.execSQL("ALTER TABLE favorites " +
                                "ADD COLUMN appWidgetProvider TEXT;");
                        db.setTransactionSuccessful();
                    } catch (SQLException ex) {
                        Log.e(TAG, ex.getMessage(), ex);
                        // Old version remains, which means we wipe old data
                        break;
                    } finally {
                        db.endTransaction();
                    }
                }
                case 14: {
                    db.beginTransaction();
                    try {
                        // Insert new column for holding update timestamp
                        db.execSQL("ALTER TABLE favorites " +
                                "ADD COLUMN modified INTEGER NOT NULL DEFAULT 0;");
                        db.execSQL("ALTER TABLE workspaceScreens " +
                                "ADD COLUMN modified INTEGER NOT NULL DEFAULT 0;");
                        db.setTransactionSuccessful();
                    } catch (SQLException ex) {
                        Log.e(TAG, ex.getMessage(), ex);
                        // Old version remains, which means we wipe old data
                        break;
                    } finally {
                        db.endTransaction();
                    }
                }
                case 15: {
                    if (!addIntegerColumn(db, Favorites.RESTORED, 0)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 16: {
                    // We use the db version upgrade here to identify users who may not have seen
                    // clings yet (because they weren't available), but for whom the clings are now
                    // available (tablet users). Because one of the possible cling flows (migration)
                    // is very destructive (wipes out workspaces), we want to prevent this from showing
                    // until clear data. We do so by marking that the clings have been shown.
                    LauncherClings.markFirstRunClingDismissed(mContext);
                }
                case 17: {
                    // No-op
                }
                case 18: {
                    // Due to a data loss bug, some users may have items associated with screen ids
                    // which no longer exist. Since this can cause other problems, and since the user
                    // will never see these items anyway, we use database upgrade as an opportunity to
                    // clean things up.
                    removeOrphanedItems(db);
                }
                case 19: {
                    // Add userId column
                    if (!addProfileColumn(db)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 20:
                    if (!updateFolderItemsRank(db, true)) {
                        break;
                    }
                case 21:
                    // Recreate workspace table with screen id a primary key
                    if (!recreateWorkspaceTable(db)) {
                        break;
                    }
                case 22: {
                    if (!addIntegerColumn(db, Favorites.OPTIONS, 0)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 23:
                    // No-op
                case 24:
                    ManagedProfileHeuristic.markExistingUsersForNoFolderCreation(mContext);
                case 25:
                    convertShortcutsToLauncherActivities(db);
                case 26: {
                    // DB Upgraded successfully
                    return;
                }
            }

            // DB was not upgraded
            Log.w(TAG, "Destroying all old data.");
            createEmptyDB(db);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This shouldn't happen -- throw our hands up in the air and start over.
            Log.w(TAG, "Database version downgrade from: " + oldVersion + " to " + newVersion +
                    ". Wiping databse.");
            createEmptyDB(db);
        }

        /**
         * Clears all the data for a fresh start.
         */
        public void createEmptyDB(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + Favorites.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + WorkspaceScreens.TABLE_NAME);
            onCreate(db);
        }

        /**
         * Replaces all shortcuts of type {@link Favorites#ITEM_TYPE_SHORTCUT} which have a valid
         * launcher activity target with {@link Favorites#ITEM_TYPE_APPLICATION}.
         */
        @Thunk void convertShortcutsToLauncherActivities(SQLiteDatabase db) {
            db.beginTransaction();
            Cursor c = null;
            SQLiteStatement updateStmt = null;

            try {
                // Only consider the primary user as other users can't have a shortcut.
                long userSerial = getDefaultUserSerial();
                c = db.query(Favorites.TABLE_NAME, new String[] {
                        Favorites._ID,
                        Favorites.INTENT,
                    }, "itemType=" + Favorites.ITEM_TYPE_SHORTCUT + " AND profileId=" + userSerial,
                    null, null, null, null);

                updateStmt = db.compileStatement("UPDATE favorites SET itemType="
                        + Favorites.ITEM_TYPE_APPLICATION + " WHERE _id=?");

                final int idIndex = c.getColumnIndexOrThrow(Favorites._ID);
                final int intentIndex = c.getColumnIndexOrThrow(Favorites.INTENT);

                while (c.moveToNext()) {
                    String intentDescription = c.getString(intentIndex);
                    Intent intent;
                    try {
                        intent = Intent.parseUri(intentDescription, 0);
                    } catch (URISyntaxException e) {
                        Log.e(TAG, "Unable to parse intent", e);
                        continue;
                    }

                    if (!Utilities.isLauncherAppTarget(intent)) {
                        continue;
                    }

                    long id = c.getLong(idIndex);
                    updateStmt.bindLong(1, id);
                    updateStmt.executeUpdateDelete();
                }
                db.setTransactionSuccessful();
            } catch (SQLException ex) {
                Log.w(TAG, "Error deduping shortcuts", ex);
            } finally {
                db.endTransaction();
                if (c != null) {
                    c.close();
                }
                if (updateStmt != null) {
                    updateStmt.close();
                }
            }
        }

        /**
         * Recreates workspace table and migrates data to the new table.
         */
        public boolean recreateWorkspaceTable(SQLiteDatabase db) {
            db.beginTransaction();
            try {
                Cursor c = db.query(WorkspaceScreens.TABLE_NAME,
                        new String[] {LauncherSettings.WorkspaceScreens._ID},
                        null, null, null, null,
                        LauncherSettings.WorkspaceScreens.SCREEN_RANK);
                ArrayList<Long> sortedIDs = new ArrayList<Long>();
                long maxId = 0;
                try {
                    while (c.moveToNext()) {
                        Long id = c.getLong(0);
                        if (!sortedIDs.contains(id)) {
                            sortedIDs.add(id);
                            maxId = Math.max(maxId, id);
                        }
                    }
                } finally {
                    c.close();
                }

                db.execSQL("DROP TABLE IF EXISTS " + WorkspaceScreens.TABLE_NAME);
                addWorkspacesTable(db, false);

                // Add all screen ids back
                int total = sortedIDs.size();
                for (int i = 0; i < total; i++) {
                    ContentValues values = new ContentValues();
                    values.put(LauncherSettings.WorkspaceScreens._ID, sortedIDs.get(i));
                    values.put(LauncherSettings.WorkspaceScreens.SCREEN_RANK, i);
                    addModifiedTime(values);
                    db.insertOrThrow(WorkspaceScreens.TABLE_NAME, null, values);
                }
                db.setTransactionSuccessful();
                mMaxScreenId = maxId;
            } catch (SQLException ex) {
                // Old version remains, which means we wipe old data
                Log.e(TAG, ex.getMessage(), ex);
                return false;
            } finally {
                db.endTransaction();
            }
            return true;
        }

        @Thunk boolean updateFolderItemsRank(SQLiteDatabase db, boolean addRankColumn) {
            db.beginTransaction();
            try {
                if (addRankColumn) {
                    // Insert new column for holding rank
                    db.execSQL("ALTER TABLE favorites ADD COLUMN rank INTEGER NOT NULL DEFAULT 0;");
                }

                // Get a map for folder ID to folder width
                Cursor c = db.rawQuery("SELECT container, MAX(cellX) FROM favorites"
                        + " WHERE container IN (SELECT _id FROM favorites WHERE itemType = ?)"
                        + " GROUP BY container;",
                        new String[] {Integer.toString(LauncherSettings.Favorites.ITEM_TYPE_FOLDER)});

                while (c.moveToNext()) {
                    db.execSQL("UPDATE favorites SET rank=cellX+(cellY*?) WHERE "
                            + "container=? AND cellX IS NOT NULL AND cellY IS NOT NULL;",
                            new Object[] {c.getLong(1) + 1, c.getLong(0)});
                }

                c.close();
                db.setTransactionSuccessful();
            } catch (SQLException ex) {
                // Old version remains, which means we wipe old data
                Log.e(TAG, ex.getMessage(), ex);
                return false;
            } finally {
                db.endTransaction();
            }
            return true;
        }

        private boolean addProfileColumn(SQLiteDatabase db) {
            return addIntegerColumn(db, Favorites.PROFILE_ID, getDefaultUserSerial());
        }

        private boolean addIntegerColumn(SQLiteDatabase db, String columnName, long defaultValue) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE favorites ADD COLUMN "
                        + columnName + " INTEGER NOT NULL DEFAULT " + defaultValue + ";");
                db.setTransactionSuccessful();
            } catch (SQLException ex) {
                Log.e(TAG, ex.getMessage(), ex);
                return false;
            } finally {
                db.endTransaction();
            }
            return true;
        }

        // Generates a new ID to use for an object in your database. This method should be only
        // called from the main UI thread. As an exception, we do call it when we call the
        // constructor from the worker thread; however, this doesn't extend until after the
        // constructor is called, and we only pass a reference to LauncherProvider to LauncherApp
        // after that point
        @Override
        public long generateNewItemId() {
            if (mMaxItemId < 0) {
                throw new RuntimeException("Error: max item id was not initialized");
            }
            mMaxItemId += 1;
            return mMaxItemId;
        }

        @Override
        public long insertAndCheck(SQLiteDatabase db, ContentValues values) {
            return dbInsertAndCheck(this, db, Favorites.TABLE_NAME, null, values);
        }

        public void checkId(String table, ContentValues values) {
            long id = values.getAsLong(LauncherSettings.BaseLauncherColumns._ID);
            if (table == WorkspaceScreens.TABLE_NAME) {
                mMaxScreenId = Math.max(id, mMaxScreenId);
            }  else {
                mMaxItemId = Math.max(id, mMaxItemId);
            }
        }

        private long initializeMaxItemId(SQLiteDatabase db) {
            return getMaxId(db, Favorites.TABLE_NAME);
        }

        // Generates a new ID to use for an workspace screen in your database. This method
        // should be only called from the main UI thread. As an exception, we do call it when we
        // call the constructor from the worker thread; however, this doesn't extend until after the
        // constructor is called, and we only pass a reference to LauncherProvider to LauncherApp
        // after that point
        public long generateNewScreenId() {
            if (mMaxScreenId < 0) {
                throw new RuntimeException("Error: max screen id was not initialized");
            }
            mMaxScreenId += 1;
            return mMaxScreenId;
        }

        private long initializeMaxScreenId(SQLiteDatabase db) {
            return getMaxId(db, WorkspaceScreens.TABLE_NAME);
        }

        @Thunk int loadFavorites(SQLiteDatabase db, AutoInstallsLayout loader) {
            ArrayList<Long> screenIds = new ArrayList<Long>();
            // TODO: Use multiple loaders with fall-back and transaction.
            int count = loader.loadLayout(db, screenIds);

            // Add the screens specified by the items above
            Collections.sort(screenIds);
            int rank = 0;
            ContentValues values = new ContentValues();
            for (Long id : screenIds) {
                values.clear();
                values.put(LauncherSettings.WorkspaceScreens._ID, id);
                values.put(LauncherSettings.WorkspaceScreens.SCREEN_RANK, rank);
                if (dbInsertAndCheck(this, db, WorkspaceScreens.TABLE_NAME, null, values) < 0) {
                    throw new RuntimeException("Failed initialize screen table"
                            + "from default layout");
                }
                rank++;
            }

            // Ensure that the max ids are initialized
            mMaxItemId = initializeMaxItemId(db);
            mMaxScreenId = initializeMaxScreenId(db);

            return count;
        }

        @Thunk void migrateLauncher2Shortcuts(SQLiteDatabase db, Uri uri) {
            final ContentResolver resolver = mContext.getContentResolver();
            Cursor c = null;
            int count = 0;
            int curScreen = 0;

            try {
                c = resolver.query(uri, null, null, null, "title ASC");
            } catch (Exception e) {
                // Ignore
            }

            // We already have a favorites database in the old provider
            if (c != null) {
                try {
                    if (c.getCount() > 0) {
                        final int idIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
                        final int intentIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT);
                        final int titleIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.TITLE);
                        final int iconIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON);
                        final int iconPackageIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON_PACKAGE);
                        final int iconResourceIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON_RESOURCE);
                        final int containerIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CONTAINER);
                        final int itemTypeIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
                        final int screenIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);
                        final int cellXIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLX);
                        final int cellYIndex
                                = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLY);
                        final int profileIndex
                                = c.getColumnIndex(LauncherSettings.Favorites.PROFILE_ID);

                        int i = 0;
                        int curX = 0;
                        int curY = 0;

                        final LauncherAppState app = LauncherAppState.getInstance();
                        final InvariantDeviceProfile profile = app.getInvariantDeviceProfile();
                        final int width = (int) profile.numColumns;
                        final int height = (int) profile.numRows;
                        final int hotseatWidth = (int) profile.numHotseatIcons;

                        final HashSet<String> seenIntents = new HashSet<String>(c.getCount());

                        final ArrayList<ContentValues> shortcuts = new ArrayList<ContentValues>();
                        final ArrayList<ContentValues> folders = new ArrayList<ContentValues>();
                        final SparseArray<ContentValues> hotseat = new SparseArray<ContentValues>();

                        while (c.moveToNext()) {
                            final int itemType = c.getInt(itemTypeIndex);
                            if (itemType != Favorites.ITEM_TYPE_APPLICATION
                                    && itemType != Favorites.ITEM_TYPE_SHORTCUT
                                    && itemType != Favorites.ITEM_TYPE_FOLDER) {
                                continue;
                            }

                            final int cellX = c.getInt(cellXIndex);
                            final int cellY = c.getInt(cellYIndex);
                            final int screen = c.getInt(screenIndex);
                            int container = c.getInt(containerIndex);
                            final String intentStr = c.getString(intentIndex);

                            UserManagerCompat userManager = UserManagerCompat.getInstance(mContext);
                            UserHandleCompat userHandle;
                            final long userSerialNumber;
                            if (profileIndex != -1 && !c.isNull(profileIndex)) {
                                userSerialNumber = c.getInt(profileIndex);
                                userHandle = userManager.getUserForSerialNumber(userSerialNumber);
                            } else {
                                // Default to the serial number of this user, for older
                                // shortcuts.
                                userHandle = UserHandleCompat.myUserHandle();
                                userSerialNumber = userManager.getSerialNumberForUser(userHandle);
                            }

                            if (userHandle == null) {
                                Log.d(TAG, "skipping deleted user");
                                continue;
                            }

                            if (itemType != Favorites.ITEM_TYPE_FOLDER) {

                                final Intent intent;
                                final ComponentName cn;
                                try {
                                    intent = Intent.parseUri(intentStr, 0);
                                } catch (URISyntaxException e) {
                                    // bogus intent?
                                    Log.d(TAG, "skipping invalid intent uri");
                                    continue;
                                }

                                cn = intent.getComponent();
                                if (TextUtils.isEmpty(intentStr)) {
                                    // no intent? no icon
                                    Log.d(TAG, "skipping empty intent");
                                    continue;
                                } else if (cn != null &&
                                        !LauncherModel.isValidPackageActivity(mContext, cn,
                                                userHandle)) {
                                    // component no longer exists.
                                    Log.d(TAG, "skipping item whose component no longer exists.");
                                    continue;
                                } else if (container ==
                                        LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                                    // Dedupe icons directly on the workspace

                                    // Canonicalize
                                    // the Play Store sets the package parameter, but Launcher
                                    // does not, so we clear that out to keep them the same.
                                    // Also ignore intent flags for the purposes of deduping.
                                    intent.setPackage(null);
                                    int flags = intent.getFlags();
                                    intent.setFlags(0);
                                    final String key = intent.toUri(0);
                                    intent.setFlags(flags);
                                    if (seenIntents.contains(key)) {
                                        Log.d(TAG, "skipping duplicate");
                                        continue;
                                    } else {
                                        seenIntents.add(key);
                                    }
                                }
                            }

                            ContentValues values = new ContentValues(c.getColumnCount());
                            values.put(LauncherSettings.Favorites._ID, c.getInt(idIndex));
                            values.put(LauncherSettings.Favorites.INTENT, intentStr);
                            values.put(LauncherSettings.Favorites.TITLE, c.getString(titleIndex));
                            values.put(LauncherSettings.Favorites.ICON, c.getBlob(iconIndex));
                            values.put(LauncherSettings.Favorites.ICON_PACKAGE,
                                    c.getString(iconPackageIndex));
                            values.put(LauncherSettings.Favorites.ICON_RESOURCE,
                                    c.getString(iconResourceIndex));
                            values.put(LauncherSettings.Favorites.ITEM_TYPE, itemType);
                            values.put(LauncherSettings.Favorites.APPWIDGET_ID, -1);
                            values.put(LauncherSettings.Favorites.PROFILE_ID, userSerialNumber);

                            if (container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                                hotseat.put(screen, values);
                            }

                            if (container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                                // In a folder or in the hotseat, preserve position
                                values.put(LauncherSettings.Favorites.SCREEN, screen);
                                values.put(LauncherSettings.Favorites.CELLX, cellX);
                                values.put(LauncherSettings.Favorites.CELLY, cellY);
                            } else {
                                // For items contained directly on one of the workspace screen,
                                // we'll determine their location (screen, x, y) in a second pass.
                            }

                            values.put(LauncherSettings.Favorites.CONTAINER, container);

                            if (itemType != Favorites.ITEM_TYPE_FOLDER) {
                                shortcuts.add(values);
                            } else {
                                folders.add(values);
                            }
                        }

                        // Now that we have all the hotseat icons, let's go through them left-right
                        // and assign valid locations for them in the new hotseat
                        final int N = hotseat.size();
                        for (int idx=0; idx<N; idx++) {
                            int hotseatX = hotseat.keyAt(idx);
                            ContentValues values = hotseat.valueAt(idx);

                            if (hotseatX == profile.hotseatAllAppsRank) {
                                // let's drop this in the next available hole in the hotseat
                                while (++hotseatX < hotseatWidth) {
                                    if (hotseat.get(hotseatX) == null) {
                                        // found a spot! move it here
                                        values.put(LauncherSettings.Favorites.SCREEN,
                                                hotseatX);
                                        break;
                                    }
                                }
                            }
                            if (hotseatX >= hotseatWidth) {
                                // no room for you in the hotseat? it's off to the desktop with you
                                values.put(LauncherSettings.Favorites.CONTAINER,
                                           Favorites.CONTAINER_DESKTOP);
                            }
                        }

                        final ArrayList<ContentValues> allItems = new ArrayList<ContentValues>();
                        // Folders first
                        allItems.addAll(folders);
                        // Then shortcuts
                        allItems.addAll(shortcuts);

                        // Layout all the folders
                        for (ContentValues values: allItems) {
                            if (values.getAsInteger(LauncherSettings.Favorites.CONTAINER) !=
                                    LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                                // Hotseat items and folder items have already had their
                                // location information set. Nothing to be done here.
                                continue;
                            }
                            values.put(LauncherSettings.Favorites.SCREEN, curScreen);
                            values.put(LauncherSettings.Favorites.CELLX, curX);
                            values.put(LauncherSettings.Favorites.CELLY, curY);
                            curX = (curX + 1) % width;
                            if (curX == 0) {
                                curY = (curY + 1);
                            }
                            // Leave the last row of icons blank on every screen
                            if (curY == height - 1) {
                                curScreen = (int) generateNewScreenId();
                                curY = 0;
                            }
                        }

                        if (allItems.size() > 0) {
                            db.beginTransaction();
                            try {
                                for (ContentValues row: allItems) {
                                    if (row == null) continue;
                                    if (dbInsertAndCheck(this, db, Favorites.TABLE_NAME, null, row)
                                            < 0) {
                                        return;
                                    } else {
                                        count++;
                                    }
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                            }
                        }

                        db.beginTransaction();
                        try {
                            for (i=0; i<=curScreen; i++) {
                                final ContentValues values = new ContentValues();
                                values.put(LauncherSettings.WorkspaceScreens._ID, i);
                                values.put(LauncherSettings.WorkspaceScreens.SCREEN_RANK, i);
                                if (dbInsertAndCheck(this, db, WorkspaceScreens.TABLE_NAME, null, values)
                                        < 0) {
                                    return;
                                }
                            }
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }

                        updateFolderItemsRank(db, false);
                    }
                } finally {
                    c.close();
                }
            }

            Log.d(TAG, "migrated " + count + " icons from Launcher2 into "
                    + (curScreen+1) + " screens");

            // Update max IDs; very important since we just grabbed IDs from another database
            mMaxItemId = initializeMaxItemId(db);
            mMaxScreenId = initializeMaxScreenId(db);
            if (LOGD) Log.d(TAG, "mMaxItemId: " + mMaxItemId + " mMaxScreenId: " + mMaxScreenId);
        }
    }

    /**
     * @return the max _id in the provided table.
     */
    @Thunk static long getMaxId(SQLiteDatabase db, String table) {
        Cursor c = db.rawQuery("SELECT MAX(_id) FROM " + table, null);
        // get the result
        long id = -1;
        if (c != null && c.moveToNext()) {
            id = c.getLong(0);
        }
        if (c != null) {
            c.close();
        }

        if (id == -1) {
            throw new RuntimeException("Error: could not query max id in " + table);
        }

        return id;
    }

    static class SqlArguments {
        public final String table;
        public final String where;
        public final String[] args;

        SqlArguments(Uri url, String where, String[] args) {
            if (url.getPathSegments().size() == 1) {
                this.table = url.getPathSegments().get(0);
                this.where = where;
                this.args = args;
            } else if (url.getPathSegments().size() != 2) {
                throw new IllegalArgumentException("Invalid URI: " + url);
            } else if (!TextUtils.isEmpty(where)) {
                throw new UnsupportedOperationException("WHERE clause not supported: " + url);
            } else {
                this.table = url.getPathSegments().get(0);
                this.where = "_id=" + ContentUris.parseId(url);
                this.args = null;
            }
        }

        SqlArguments(Uri url) {
            if (url.getPathSegments().size() == 1) {
                table = url.getPathSegments().get(0);
                where = null;
                args = null;
            } else {
                throw new IllegalArgumentException("Invalid URI: " + url);
            }
        }
    }

    private static class ChangeListenerWrapper implements Handler.Callback {

        private static final int MSG_LAUNCHER_PROVIDER_CHANGED = 1;
        private static final int MSG_EXTRACTED_COLORS_CHANGED = 2;
        private static final int MSG_APP_WIDGET_HOST_RESET = 3;

        private LauncherProviderChangeListener mListener;

        @Override
        public boolean handleMessage(Message msg) {
            if (mListener != null) {
                switch (msg.what) {
                    case MSG_LAUNCHER_PROVIDER_CHANGED:
                        mListener.onLauncherProviderChange();
                        break;
                    case MSG_EXTRACTED_COLORS_CHANGED:
                        mListener.onExtractedColorsChanged();
                        break;
                    case MSG_APP_WIDGET_HOST_RESET:
                        mListener.onAppWidgetHostReset();
                        break;
                }
            }
            return true;
        }
    }
}
