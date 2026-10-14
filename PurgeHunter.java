package com.example.purgehunter;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class PurgeHunter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ADMIN = "purgehunter.admin";

    private boolean active = false;
    private UUID hunter;
    private String hunterName = "None";
    private final Set<UUID> eliminated = new HashSet<>();
    private int totalTime;
    private int timeLeft;
    private int safeLeft;
    private BossBar bar;
    private BossBar hunterBar;
    private BukkitTask task;
    private Scoreboard board;
    private Objective objective;

    /** &-codes to section-sign colour codes */
    private static String c(String s) {
        return s.replace('&', '\u00a7');
    }

    private static String clock(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("purge");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("purgehud", Criteria.DUMMY, c("&4&lPURGE HUNTER"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(board);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (active) {
            end(null);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    // ------------------------------------------------------------ purge logic

    private int aliveRunners() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(hunter) && !eliminated.contains(p.getUniqueId())) {
                n++;
            }
        }
        return n;
    }

    private boolean start(Player chosen) {
        List<Player> pool = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (pool.size() < 2) {
            return false;
        }
        Player h = chosen != null ? chosen : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));

        active = true;
        hunter = h.getUniqueId();
        hunterName = h.getName();
        eliminated.clear();
        totalTime = Math.max(30, getConfig().getInt("purge-duration-seconds", 600));
        timeLeft = totalTime;
        safeLeft = Math.max(0, getConfig().getInt("safe-seconds", 30));

        bar = Bukkit.createBossBar(c("&4&lPURGE ACTIVE"), BarColor.RED, BarStyle.SOLID);
        bar.setProgress(1.0);
        hunterBar = Bukkit.createBossBar(c("&eHunting..."), BarColor.YELLOW, BarStyle.SOLID);
        hunterBar.setProgress(1.0);
        hunterBar.addPlayer(h);

        for (Player p : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(p);
            if (p.getUniqueId().equals(hunter)) {
                p.sendTitle(c("&4&lYOU ARE THE HUNTER"), c("&cFind them. Eliminate them."), 10, 70, 20);
            } else {
                p.sendTitle(c("&4&lPURGE STARTED"), c("&cRUN! &f" + hunterName + " &cis hunting you"), 10, 70, 20);
            }
        }

        int ticks = (totalTime + 5) * 20;
        if (getConfig().getBoolean("hunter-speed", true)) {
            h.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 0, false, false));
        }
        if (getConfig().getBoolean("hunter-glowing", true)) {
            h.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false));
        }
        if (getConfig().getBoolean("hunter-compass", true)) {
            h.getInventory().addItem(new ItemStack(Material.COMPASS));
        }

        Bukkit.broadcastMessage(c("&4&lPURGE STARTED! &f" + hunterName + " &cis the Purge Hunter."));
        if (safeLeft > 0) {
            Bukkit.broadcastMessage(c("&eThe hunter cannot attack for the first " + safeLeft + " seconds."));
        }
        return true;
    }

    /** runnersWin: TRUE = runners win, FALSE = hunter wins, null = stopped by admin / shutdown */
    private void end(Boolean runnersWin) {
        if (!active) {
            return;
        }
        active = false;

        String sub;
        if (runnersWin == null) {
            sub = "&7The purge was stopped";
        } else if (runnersWin) {
            sub = "&aThe runners win!";
        } else {
            sub = "&cThe hunter wins!";
        }

        if (bar != null) {
            bar.removeAll();
        }
        if (hunterBar != null) {
            hunterBar.removeAll();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.GLOWING);
            if (eliminated.contains(p.getUniqueId()) && p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
            }
            p.sendTitle(c("&a&lPURGE ENDED"), c(sub), 10, 70, 20);
        }
        Bukkit.broadcastMessage(c("&a&lPURGE ENDED! " + sub));

        bar = null;
        hunterBar = null;
        hunter = null;
        hunterName = "None";
        eliminated.clear();
        safeLeft = 0;
    }

    private void tick() {
        if (active) {
            timeLeft--;
            if (safeLeft > 0) {
                safeLeft--;
                if (safeLeft == 0) {
                    Bukkit.broadcastMessage(c("&4&lThe safe period is over! &cThe hunt is on!"));
                }
            }
            if (timeLeft <= 0) {
                end(Boolean.TRUE);
            } else {
                if (bar != null) {
                    bar.setProgress(Math.max(0.0, Math.min(1.0, (double) timeLeft / totalTime)));
                    bar.setTitle(c("&4&lPURGE ACTIVE &7| &f" + clock(timeLeft) + " &7| &cHunter: &f" + hunterName));
                }
                updateHunterBar();
            }
        }
        updateBoard();
    }

    private void updateHunterBar() {
        Player h = hunter == null ? null : Bukkit.getPlayer(hunter);
        if (h == null || hunterBar == null) {
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(hunter) || eliminated.contains(p.getUniqueId())) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!Objects.equals(p.getWorld(), h.getWorld())) continue;
            double d = p.getLocation().distanceSquared(h.getLocation());
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (safeLeft > 0) {
            hunterBar.setTitle(c("&eSafe period: you can't attack for " + safeLeft + "s"));
        } else if (nearest != null) {
            h.setCompassTarget(nearest.getLocation());
            hunterBar.setTitle(c("&eNearest runner: &f" + (int) Math.sqrt(best) + " blocks"));
        } else {
            hunterBar.setTitle(c("&eNo runner in your world"));
        }
    }

    private void updateBoard() {
        for (String entry : new HashSet<>(board.getEntries())) {
            board.resetScores(entry);
        }
        int players = active ? aliveRunners() : Bukkit.getOnlinePlayers().size();
        List<String> lines = new ArrayList<>();
        lines.add(c("&7Status: " + (active ? "&c&lPURGE" : "&a&lSAFE")));
        lines.add(c("&7Hunter: &f" + hunterName));
        lines.add(c("&7" + (active ? "Runners alive: " : "Players: ") + "&f" + players));
        if (active) {
            lines.add(c("&7Time left: &f" + clock(timeLeft)));
            if (safeLeft > 0) {
                lines.add(c("&eSafe for: " + safeLeft + "s"));
            }
        }
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setScoreboard(board);
        if (active && bar != null) {
            bar.addPlayer(p);
        }
        p.sendTitle(c("&4&lWELCOME"), c("&cTO PURGE HUNTER SMP"), 10, 60, 20);
        p.sendMessage(c("&cWelcome to Purge Hunter SMP!"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!active) {
            return;
        }
        if (e.getPlayer().getUniqueId().equals(hunter)) {
            Bukkit.broadcastMessage(c("&eThe hunter left the game!"));
            end(Boolean.TRUE);
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                if (active && aliveRunners() == 0) {
                    end(Boolean.FALSE);
                }
            });
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!active) {
            return;
        }
        Player dead = e.getEntity();
        if (dead.getUniqueId().equals(hunter)) {
            Bukkit.broadcastMessage(c("&a&lThe hunter has fallen! &f" + dead.getName() + " &7died."));
            end(Boolean.TRUE);
            return;
        }
        if (eliminated.add(dead.getUniqueId())) {
            Player killer = dead.getKiller();
            if (killer != null && killer.getUniqueId().equals(hunter)) {
                Bukkit.broadcastMessage(c("&c" + dead.getName() + " was eliminated by the hunter."));
            } else {
                Bukkit.broadcastMessage(c("&c" + dead.getName() + " was eliminated."));
            }
            if (aliveRunners() == 0) {
                end(Boolean.FALSE);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (active && eliminated.contains(p.getUniqueId())
                && getConfig().getBoolean("eliminated-become-spectators", true)) {
            Bukkit.getScheduler().runTask(this, () -> p.setGameMode(GameMode.SPECTATOR));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!active || safeLeft <= 0) {
            return;
        }
        if (e.getDamager() instanceof Player damager && damager.getUniqueId().equals(hunter)
                && e.getEntity() instanceof Player) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("status") && !s.hasPermission(ADMIN)) {
            s.sendMessage(c("&cYou don't have permission."));
            return true;
        }
        switch (sub) {
            case "start" -> {
                if (active) {
                    s.sendMessage(c("&cA purge is already running."));
                    return true;
                }
                Player chosen = null;
                if (a.length > 1) {
                    chosen = Bukkit.getPlayerExact(a[1]);
                    if (chosen == null) {
                        s.sendMessage(c("&cPlayer not found."));
                        return true;
                    }
                }
                if (!start(chosen)) {
                    s.sendMessage(c("&cYou need at least 2 players online."));
                }
            }
            case "stop" -> {
                if (!active) {
                    s.sendMessage(c("&cNo purge is running."));
                } else {
                    end(null);
                }
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage(c("&aConfig reloaded."));
            }
            case "status" -> {
                if (active) {
                    s.sendMessage(c("&cPurge active. &7Hunter: &f" + hunterName + " &7| Time left: &f"
                            + clock(timeLeft) + " &7| Runners alive: &f" + aliveRunners()));
                } else {
                    s.sendMessage(c("&aNo purge running. &7Use /purge start"));
                }
            }
            default -> s.sendMessage(c("&7Usage: /purge <start [player]|stop|status|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : new String[]{"start", "stop", "status", "reload"}) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("start")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
