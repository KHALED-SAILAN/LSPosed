/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.repo;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import org.lsposed.manager.App;
import org.lsposed.manager.repo.model.OnlineModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RepoLoader {
    private static RepoLoader instance = null;
    private Map<String, OnlineModule> onlineModules = new HashMap<>();

    public static class ModuleVersion {
        public String versionName;
        public long versionCode;
        private ModuleVersion(long versionCode, String versionName) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
        public boolean upgradable(long versionCode, String versionName) {
            return this.versionCode > versionCode || (this.versionCode == versionCode && !versionName.equals(this.versionName));
        }

    }

    private final Map<String, ModuleVersion> latestVersion = new ConcurrentHashMap<>();
    private final Path repoFile = Paths.get(App.getInstance().getFilesDir().getAbsolutePath(), "repo.json");
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean isLoading = false;
    private boolean repoLoaded = false;
    private static final String originRepoUrl = "https://modules.lsposed.org/";
    private static final String backupRepoUrl = "https://cdn.jsdelivr.net/gh/Xposed-Modules-Repo/modules@gh-pages/";
    private static String repoUrl = originRepoUrl;

    public boolean isRepoLoaded() {
        return repoLoaded;
    }

    public static synchronized RepoLoader getInstance() {
        if (instance == null) {
            instance = new RepoLoader();
            instance.loadRemoteData();
        }
        return instance;
    }

    public void loadRemoteData() {
        synchronized (this) {
            if (isLoading) {
                return;
            }
            isLoading = true;
        }
        App.getOkHttpClient().newCall(new Request.Builder()
                .url(repoUrl + "modules.json")
                .build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(App.TAG, call.request().url().toString(), e);
                for (Listener listener : listeners) {
                    listener.onThrowable(e);
                }
                synchronized (this) {
                    isLoading = false;
                    if (!repoUrl.equals(backupRepoUrl)) {
                        repoUrl = backupRepoUrl;
                        loadRemoteData();
                    }
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    if (body != null) {
                        try {
                            String bodyString = body.string();
                            Gson gson = new Gson();
                            Map<String, OnlineModule> modules = new HashMap<>();
                            OnlineModule[] repoModules = gson.fromJson(bodyString, OnlineModule[].class);
                            Arrays.stream(repoModules).forEach(onlineModule -> modules.put(onlineModule.getName(), onlineModule));

                            latestVersion.clear();
                            for (var module : repoModules) {
                                var release = module.getLatestRelease();
                                if (release == null || release.isEmpty()) continue;
                                var splits = release.split("-", 2);
                                if (splits.length < 2) continue;
                                long verCode;
                                String verName;
                                try {
                                    verCode = Long.parseLong(splits[0]);
                                    verName = splits[1];
                                } catch (NumberFormatException ignored) {
                                    continue;
                                }
                                String pkgName = module.getName();
                                latestVersion.put(pkgName, new ModuleVersion(verCode, verName));
                            }

                            onlineModules = modules;
                            Files.write(repoFile, bodyString.getBytes(StandardCharsets.UTF_8));
                            synchronized (this) {
                                repoLoaded = true;
                            }
                            for (Listener listener : listeners) {
                                listener.repoLoaded();
                            }
                        } catch (Throwable t) {
                            Log.e(App.TAG, Log.getStackTraceString(t));
                            for (Listener listener : listeners) {
                                listener.onThrowable(t);
                            }
                        }
                    }
                }
                synchronized (this) {
                    isLoading = false;
                }
            }
        });
    }

    public ModuleVersion getModuleLatestVersion(String packageName) {
        return latestVersion.get(packageName);
    }

    public void loadRemoteReleases(String packageName) {
        App.getOkHttpClient().newCall(new Request.Builder()
                .url(String.format(repoUrl + "module/%s.json", packageName))
                .build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(App.TAG, call.request().url() + e.getMessage());
                if (!repoUrl.equals(backupRepoUrl)) {
                    repoUrl = backupRepoUrl;
                    loadRemoteReleases(packageName);
                } else {
                    for (Listener listener : listeners) {
                        listener.onThrowable(e);
                    }
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    if (body != null) {
                        try {
                            String bodyString = body.string();
                            Gson gson = new Gson();
                            OnlineModule module = gson.fromJson(bodyString, OnlineModule.class);
                            module.releasesLoaded = true;
                            onlineModules.replace(packageName, module);
                            for (Listener listener : listeners) {
                                listener.moduleReleasesLoaded(module);
                            }
                        } catch (Throwable t) {
                            Log.e(App.TAG, Log.getStackTraceString(t));
                            for (Listener listener : listeners) {
                                listener.onThrowable(t);
                            }
                        }
                    }
                }
            }
        });
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public OnlineModule getOnlineModule(String packageName) {
        return packageName == null ? null : onlineModules.get(packageName);
    }

    public Collection<OnlineModule> getOnlineModules() {
        return onlineModules.values();
    }

    public interface Listener {
        default void repoLoaded() {
        }

        default void moduleReleasesLoaded(OnlineModule module) {
        }

        default void onThrowable(Throwable t) {
            Log.e(App.TAG, "load repo failed", t);
        }
    }
}
