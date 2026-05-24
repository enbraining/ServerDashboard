package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.api.DashboardModule;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class ModuleManager {
    private final DashboardPlugin plugin;
    private final File modulesDir;
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();

    public ModuleManager(DashboardPlugin plugin) {
        this.plugin = plugin;
        this.modulesDir = new File(plugin.getDataFolder(), "modules");
        if (!modulesDir.exists()) modulesDir.mkdirs();
    }

    public void loadAll() {
        File[] jars = modulesDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) return;
        Arrays.sort(jars, Comparator.comparing(File::getName));
        for (File jar : jars) loadJar(jar);
    }

    public boolean loadJar(File jar) {
        try {
            URLClassLoader cl = new URLClassLoader(
                    new URL[]{jar.toURI().toURL()},
                    plugin.getClass().getClassLoader()
            );
            ServiceLoader<DashboardModule> sl = ServiceLoader.load(DashboardModule.class, cl);
            boolean found = false;
            for (DashboardModule m : sl) {
                String id = m.getId();
                if (id == null || id.isBlank() || !id.matches("[a-z0-9_-]+")) {
                    plugin.getLogger().warning("[Modules] Invalid module id '" + id + "' in " + jar.getName() + " — skipped.");
                    continue;
                }
                if (modules.containsKey(id)) {
                    plugin.getLogger().warning("[Modules] Duplicate module id '" + id + "' in " + jar.getName() + " — skipped.");
                    continue;
                }
                try {
                    m.onLoad(plugin);
                } catch (Exception e) {
                    plugin.getLogger().severe("[Modules] onLoad() failed for '" + id + "': " + e.getMessage());
                    continue;
                }
                modules.put(id, new LoadedModule(m, cl, jar));
                plugin.getLogger().info("[Modules] Loaded: " + m.getName() + " (id=" + id + ")");
                found = true;
            }
            if (!found) {
                plugin.getLogger().warning("[Modules] No DashboardModule found in " + jar.getName()
                        + ". Make sure META-INF/services/com.serverdashboard.api.DashboardModule exists.");
                cl.close();
                return false;
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Modules] Failed to load " + jar.getName() + ": " + e.getMessage());
            return false;
        }
    }

    public void unloadAll() {
        for (Map.Entry<String, LoadedModule> entry : modules.entrySet()) {
            unloadSingle(entry.getKey(), entry.getValue());
        }
        modules.clear();
    }

    public boolean unload(String id) {
        LoadedModule lm = modules.remove(id);
        if (lm == null) return false;
        unloadSingle(id, lm);
        return true;
    }

    private void unloadSingle(String id, LoadedModule lm) {
        try { lm.module.onUnload(); } catch (Exception e) {
            plugin.getLogger().warning("[Modules] onUnload() error for '" + id + "': " + e.getMessage());
        }
        try { lm.classLoader.close(); } catch (Exception ignored) {}
        plugin.getLogger().info("[Modules] Unloaded: " + id);
    }

    public void reloadAll() {
        unloadAll();
        loadAll();
    }

    public Collection<DashboardModule> getAll() {
        List<DashboardModule> list = new ArrayList<>();
        for (LoadedModule lm : modules.values()) list.add(lm.module);
        return Collections.unmodifiableList(list);
    }

    public DashboardModule get(String id) {
        LoadedModule lm = modules.get(id);
        return lm != null ? lm.module : null;
    }

    public int count() { return modules.size(); }

    private record LoadedModule(DashboardModule module, URLClassLoader classLoader, File jar) {}
}
