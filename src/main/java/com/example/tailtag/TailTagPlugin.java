package com.example.tailtag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class TailTagPlugin extends JavaPlugin implements Listener {
    
    private Map<UUID, TeamColor> playerColors = new HashMap<>();
    private Map<UUID, UUID> slaves = new HashMap<>(); // 노예 UUID -> 주인 UUID
    private Map<UUID, Set<UUID>> masters = new HashMap<>(); // 주인 UUID -> 노예들 Set
    private Map<UUID, Long> deadPlayers = new HashMap<>(); // 자연사한 플레이어와 사망 시간
    private Map<UUID, Integer> frozenPlayers = new HashMap<>(); // 움직일 수 없는 플레이어
    private boolean gameActive = false;
    private Location gameCenter;
    private final int GAME_AREA_SIZE = 20; // 20청크
    private BukkitTask gameTask;
    private BukkitTask heartbeatTask;
    
    public enum TeamColor {
        RED(ChatColor.RED, "빨강"),
        ORANGE(ChatColor.GOLD, "주황"),
        YELLOW(ChatColor.YELLOW, "노랑"),
        GREEN(ChatColor.GREEN, "초록"),
        BLUE(ChatColor.BLUE, "파랑"),
        INDIGO(ChatColor.DARK_BLUE, "남색"),
        PURPLE(ChatColor.DARK_PURPLE, "보라"),
        PINK(ChatColor.LIGHT_PURPLE, "핑크"),
        GRAY(ChatColor.GRAY, "회색"),
        BLACK(ChatColor.BLACK, "검정");
        
        private final ChatColor chatColor;
        private final String displayName;
        
        TeamColor(ChatColor chatColor, String displayName) {
            this.chatColor = chatColor;
            this.displayName = displayName;
        }
        
        public ChatColor getChatColor() {
            return chatColor;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("꼬리잡기 플러그인이 활성화되었습니다!");
        
        // 게임 상태 체크 태스크
        startGameTasks();
    }
    
    @Override
    public void onDisable() {
        if (gameTask != null) {
            gameTask.cancel();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        getLogger().info("꼬리잡기 플러그인이 비활성화되었습니다!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tailtag")) {
            return false;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage("플레이어만 사용할 수 있는 명령어입니다.");
            return true;
        }
        
        Player player = (Player) sender;
        
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "사용법: /tailtag <start|reset>");
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "start":
                startGame(player);
                break;
            case "reset":
                resetGame(player);
                break;
            default:
                player.sendMessage(ChatColor.RED + "사용법: /tailtag <start|reset>");
                break;
        }
        
        return true;
    }
    
    private void startGame(Player commander) {
        if (gameActive) {
            commander.sendMessage(ChatColor.RED + "게임이 이미 진행 중입니다.");
            return;
        }
        
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.size() < 2) {
            commander.sendMessage(ChatColor.RED + "최소 2명의 플레이어가 필요합니다.");
            return;
        }
        
        if (onlinePlayers.size() > 10) {
            commander.sendMessage(ChatColor.RED + "최대 10명까지만 게임에 참여할 수 있습니다.");
            return;
        }
        
        gameActive = true;
        gameCenter = commander.getLocation();
        
        // 플레이어 색깔 배정
        assignColors(new ArrayList<>(onlinePlayers));
        
        // 플레이어 스폰
        spawnPlayers(new ArrayList<>(onlinePlayers));
        
        // 게임 시작 안내
        for (Player player : onlinePlayers) {
            TeamColor color = playerColors.get(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "게임이 시작되었습니다!");
            player.sendMessage(color.getChatColor() + "당신의 색깔은 " + color.getDisplayName() + "입니다.");
            
            TeamColor targetColor = getTargetColor(color, onlinePlayers.size());
            if (targetColor != null) {
                player.sendMessage(ChatColor.YELLOW + "잡아야 할 색깔: " + targetColor.getChatColor() + targetColor.getDisplayName());
            }
            
            // 인벤토리 저장
            saveInventory(player);
            
            // 드래곤 알 지급
            player.getInventory().addItem(new ItemStack(Material.DRAGON_EGG, 1));
            player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        }
        
        commander.sendMessage(ChatColor.GREEN + "게임이 시작되었습니다!");
    }
    
    private void resetGame(Player commander) {
        gameActive = false;
        
        // 모든 플레이어 상태 초기화
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreInventory(player);
            player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            player.clearActivePotionEffects();
            player.setFireTicks(0);
            player.setFoodLevel(20);
            player.setSaturation(20);
        }
        
        // 데이터 초기화
        playerColors.clear();
        slaves.clear();
        masters.clear();
        deadPlayers.clear();
        frozenPlayers.clear();
        
        if (gameTask != null) {
            gameTask.cancel();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        
        startGameTasks();
        
        commander.sendMessage(ChatColor.GREEN + "게임이 리셋되었습니다.");
    }
    
    private void assignColors(List<Player> players) {
        Collections.shuffle(players);
        TeamColor[] colors = TeamColor.values();
        
        for (int i = 0; i < players.size(); i++) {
            TeamColor color = colors[i % players.size()];
            playerColors.put(players.get(i).getUniqueId(), color);
            masters.put(players.get(i).getUniqueId(), new HashSet<>());
        }
    }
    
    private void spawnPlayers(List<Player> players) {
        Random random = new Random();
        World world = gameCenter.getWorld();
        
        for (Player player : players) {
            // 20청크 범위 내 랜덤 위치 생성
            int offsetX = (random.nextInt(GAME_AREA_SIZE * 2) - GAME_AREA_SIZE) * 16;
            int offsetZ = (random.nextInt(GAME_AREA_SIZE * 2) - GAME_AREA_SIZE) * 16;
            
            Location spawnLocation = new Location(world, 
                gameCenter.getX() + offsetX, 
                world.getHighestBlockYAt(gameCenter.getBlockX() + offsetX, gameCenter.getBlockZ() + offsetZ) + 1,
                gameCenter.getZ() + offsetZ);
            
            player.teleport(spawnLocation);
        }
    }
    
    private TeamColor getTargetColor(TeamColor currentColor, int totalPlayers) {
        TeamColor[] colors = Arrays.copyOf(TeamColor.values(), totalPlayers);
        
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == currentColor) {
                return colors[(i + 1) % colors.length];
            }
        }
        return null;
    }
    
    private void saveInventory(Player player) {
        // 인벤토리 완전 초기화
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        
        // 상태 초기화
        player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.clearActivePotionEffects();
        player.setExp(0);
        player.setLevel(0);
    }
    
    private void restoreInventory(Player player) {
        // 게임 종료 후 상태 초기화
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        
        // 체력과 상태 복원
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.clearActivePotionEffects();
        player.setExp(0);
        player.setLevel(0);
    }
    
    private void startGameTasks() {
        // 게임 상태 체크 태스크 (1초마다)
        gameTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameActive) return;
                
                checkGameEnd();
                checkSlaveDistance();
                checkDeadPlayers();
                checkFrozenPlayers();
                updateDragonEggEffects();
            }
        }.runTaskTimer(this, 20L, 20L);
        
        // 하트비트 표시 태스크 (1초마다)
        heartbeatTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameActive) return;
                showHeartbeat();
            }
        }.runTaskTimer(this, 20L, 20L);
    }
    
    private void checkGameEnd() {
        if (!gameActive) return;
        
        Set<UUID> activeMasters = new HashSet<>();
        for (Map.Entry<UUID, Set<UUID>> entry : masters.entrySet()) {
            UUID masterUUID = entry.getKey();
            Player master = Bukkit.getPlayer(masterUUID);
            
            if (master != null && master.isOnline() && !slaves.containsKey(masterUUID)) {
                activeMasters.add(masterUUID);
            }
        }
        
        if (activeMasters.size() == 1) {
            UUID winnerUUID = activeMasters.iterator().next();
            Player winner = Bukkit.getPlayer(winnerUUID);
            
            if (winner != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendTitle(ChatColor.GOLD + winner.getName() + "님이 승리하셨습니다!", 
                                   "", 10, 70, 20);
                }
                
                // 게임 종료 후 3초 뒤 리셋
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        resetGame(winner);
                    }
                }.runTaskLater(this, 60L);
            }
        }
    }
    
    private void checkSlaveDistance() {
        for (Map.Entry<UUID, UUID> entry : slaves.entrySet()) {
            UUID slaveUUID = entry.getKey();
            UUID masterUUID = entry.getValue();
            
            Player slave = Bukkit.getPlayer(slaveUUID);
            Player master = Bukkit.getPlayer(masterUUID);
            
            if (slave != null && master != null && slave.isOnline() && master.isOnline()) {
                double distance = slave.getLocation().distance(master.getLocation());
                
                if (distance > 30) {
                    // 노예에게 데미지
                    slave.damage(1.0); // 0.5 하트 데미지
                    
                    if (slave.getHealth() <= 0) {
                        slave.teleport(master.getLocation());
                        slave.setHealth(slave.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
                    }
                }
            }
        }
    }
    
    private void checkDeadPlayers() {
        Iterator<Map.Entry<UUID, Long>> iterator = deadPlayers.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            UUID playerUUID = entry.getKey();
            long deathTime = entry.getValue();
            
            if (System.currentTimeMillis() - deathTime >= 120000) { // 2분
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 1200, 0)); // 1분
                    frozenPlayers.remove(playerUUID);
                }
                iterator.remove();
            }
        }
    }
    
    private void checkFrozenPlayers() {
        Iterator<Map.Entry<UUID, Integer>> iterator = frozenPlayers.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            UUID playerUUID = entry.getKey();
            int timeLeft = entry.getValue() - 1;
            
            if (timeLeft <= 0) {
                iterator.remove();
            } else {
                frozenPlayers.put(playerUUID, timeLeft);
                
                // 플레이어 이동 제한
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 255, false, false));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 25, -10, false, false));
                }
            }
        }
    }
    
    private void updateDragonEggEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getInventory().contains(Material.DRAGON_EGG)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 25, 1, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 25, 0, false, false));
            }
        }
    }
    
    private void showHeartbeat() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!gameActive) continue;
            
            UUID playerUUID = player.getUniqueId();
            
            if (slaves.containsKey(playerUUID)) {
                // 노예인 경우 주인과의 거리 표시
                UUID masterUUID = slaves.get(playerUUID);
                Player master = Bukkit.getPlayer(masterUUID);
                
                if (master != null && master.isOnline()) {
                    double distance = player.getLocation().distance(master.getLocation());
                    player.sendActionBar(ChatColor.YELLOW + "주인과의 거리: " + (int)distance + "블럭");
                }
            } else {
                // 주인인 경우 추적자 감지
                TeamColor playerColor = playerColors.get(playerUUID);
                if (playerColor != null) {
                    TeamColor hunterColor = getHunterColor(playerColor, Bukkit.getOnlinePlayers().size());
                    
                    if (hunterColor != null) {
                        for (Player other : Bukkit.getOnlinePlayers()) {
                            TeamColor otherColor = playerColors.get(other.getUniqueId());
                            
                            if (otherColor == hunterColor && !slaves.containsKey(other.getUniqueId())) {
                                double distance = player.getLocation().distance(other.getLocation());
                                
                                if (distance <= 30) {
                                    player.sendActionBar(ChatColor.RED + "❤");
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private TeamColor getHunterColor(TeamColor currentColor, int totalPlayers) {
        TeamColor[] colors = Arrays.copyOf(TeamColor.values(), totalPlayers);
        
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == currentColor) {
                return colors[(i - 1 + colors.length) % colors.length];
            }
        }
        return null;
    }
    
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!gameActive) return;
        
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        
        event.setDeathMessage(""); // 킬로그 숨김
        
        if (killer != null && killer instanceof Player) {
            UUID victimUUID = victim.getUniqueId();
            UUID killerUUID = killer.getUniqueId();
            
            TeamColor victimColor = playerColors.get(victimUUID);
            TeamColor killerColor = playerColors.get(killerUUID);
            
            // 올바른 색깔 순서로 잡았는지 확인
            TeamColor targetColor = getTargetColor(killerColor, Bukkit.getOnlinePlayers().size());
            
            if (targetColor == victimColor) {
                // 노예로 만들기
                slaves.put(victimUUID, killerUUID);
                masters.get(killerUUID).add(victimUUID);
                
                // 노예 체력 제한 (4칸 = 8.0)
                victim.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(8.0);
                victim.setHealth(8.0);
                
                // 주인 체력 감소
                double currentMaxHealth = killer.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
                killer.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(Math.max(2.0, currentMaxHealth - 2.0));
                
                // 메시지 전송
                victim.sendMessage(ChatColor.RED + killer.getName() + "님의 노예가 되었습니다.");
                killer.sendMessage(ChatColor.GREEN + victim.getName() + "님이 노예가 되었습니다.");
                
                // 노예를 주인 위치로 텔레포트
                victim.teleport(killer.getLocation());
            }
        } else {
            // 자연사
            UUID victimUUID = victim.getUniqueId();
            deadPlayers.put(victimUUID, System.currentTimeMillis());
            frozenPlayers.put(victimUUID, 120); // 120초
            
            victim.sendMessage(ChatColor.RED + "자연사로 인해 2분간 움직일 수 없습니다.");
        }
    }
    
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!gameActive) return;
        
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        
        if (item != null && item.getType() == Material.DIAMOND && 
            (event.getAction().name().contains("RIGHT_CLICK"))) {
            
            UUID playerUUID = player.getUniqueId();
            TeamColor playerColor = playerColors.get(playerUUID);
            
            if (playerColor != null) {
                TeamColor targetColor = getTargetColor(playerColor, Bukkit.getOnlinePlayers().size());
                
                if (targetColor != null) {
                    Player target = findPlayerWithColor(targetColor);
                    
                    if (target != null) {
                        if (!target.getWorld().equals(player.getWorld())) {
                            player.sendMessage(ChatColor.RED + "타겟이 같은 월드에 존재하지 않습니다.");
                            return;
                        }
                        
                        // 다이아 소모
                        item.setAmount(item.getAmount() - 1);
                        
                        // 방향 표시
                        showDirectionToTarget(player, target);
                    }
                }
            }
        }
    }
    
    private Player findPlayerWithColor(TeamColor color) {
        for (Map.Entry<UUID, TeamColor> entry : playerColors.entrySet()) {
            if (entry.getValue() == color) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline() && !slaves.containsKey(entry.getKey())) {
                    return player;
                }
            }
        }
        return null;
    }
    
    private void showDirectionToTarget(Player player, Player target) {
        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();
        
        // 방향 벡터 계산
        double dx = targetLoc.getX() - playerLoc.getX();
        double dy = targetLoc.getY() - playerLoc.getY();
        double dz = targetLoc.getZ() - playerLoc.getZ();
        
        // 정규화
        double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
        dx /= length;
        dy /= length;
        dz /= length;
        
        // 4블럭 거리의 선분으로 표시
        for (int i = 1; i <= 4; i++) {
            Location particleLoc = playerLoc.clone().add(dx*i, dy*i + 1.5, dz*i);
            player.getWorld().spawnParticle(Particle.REDSTONE, particleLoc, 1, 
                new Particle.DustOptions(org.bukkit.Color.RED, 1.0f));
        }
        
        player.sendMessage(ChatColor.YELLOW + "타겟 방향을 표시했습니다!");
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (gameActive) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "게임이 진행 중입니다.");
        }
    }
    
    @EventHandler
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        // 발전과제 메시지 숨김
        event.message(null);
    }
}
