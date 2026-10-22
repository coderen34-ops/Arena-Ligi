package me.arenaligi.zindan;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.YamlDosya;

/**
 * Yer altı Zindan sistemi (Faz 5, Entry #10'un derinleştirilmiş hali - bkz. Plugin Changelog).
 * Katman katman aşağı inen, derinleştikçe canlıların güçlendiği, her birinin rastgele bir varyantı
 * (HIZLI: daha çok hız/daha az hasar, GUCLU: daha yavaş/daha çok hasar + uzun menzil simülasyonu)
 * olan, en altta 3 varyanttan birinin boss'u bekleyen prosedürel zindanlar. Vahşi Kale'deki gibi:
 * bellekte tutulur, zindanlar.yml'e yazılır/okunur; fiziksel yapı (bkz. ZindanInsaatYardimcisi) bir
 * kere inşa edilir, kat canlıları bir bekçi tick'iyle (ölünce/restart sonrası) tamamlanır.
 *
 * Hazine Avı (EtkinlikYonetici) bu sınıfa taşınmadı - ayrı, basit bir mekanik olarak orada kalıyor.
 * Eski basit "/arena admin zindan kur <seviye>" (tek boss, el ile yerleştirilen) tamamen bu derin
 * sisteme dönüştürüldü.
 */
public class ZindanYoneticisi implements Listener {

    private enum Varyant { NORMAL, HIZLI, GUCLU }

    private static final String[] ISIM_ONEKLERI = {"Unutulmuş", "Gömülü", "Kara", "Lanetli", "Kadim", "Karanlık", "Yankılanan", "Kayıp"};
    private static final String[] ISIM_SONEKLERI = {"Mahzen", "Labirent", "Galeri", "Tapınak", "Çukur", "Zindan", "Oyuk", "Mezar"};

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final NamespacedKey zindanKey;
    private final NamespacedKey katKey;
    private final NamespacedKey bossMuKey;

    private final Map<UUID, Zindan> zindanlar = new LinkedHashMap<>();
    private final Set<UUID> uzunMenzilliCanlilar = new LinkedHashSet<>();
    private final Map<UUID, Long> uzunMenzilSonVurus = new LinkedHashMap<>();

    private int bekciSayaci;
    private int yakinlikSayaci;

    public ZindanYoneticisi(ArenaLigi plugin) {
        this.plugin = plugin;
        this.zindanKey = new NamespacedKey(plugin, "zindan-id");
        this.katKey = new NamespacedKey(plugin, "zindan-kat");
        this.bossMuKey = new NamespacedKey(plugin, "zindan-boss");
        this.dosya = new YamlDosya(plugin, "zindanlar.yml", this::olustur);
        yukle();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ------------------------------------------------------------------ AYARLAR
    private int minAktifZindan() { return plugin.getConfig().getInt("zindan.min-aktif-zindan-sayisi", 2); }
    private double minSpawnUzakligi() { return plugin.getConfig().getDouble("zindan.min-spawn-uzakligi", 1200); }
    private double maxSpawnUzakligi() { return plugin.getConfig().getDouble("zindan.max-spawn-uzakligi", 6000); }
    private double zindanlarArasiMin() { return plugin.getConfig().getDouble("zindan.zindanlar-arasi-min-uzaklik", 400); }
    private double yakinlikEsigi() { return plugin.getConfig().getDouble("zindan.yakinlik-esigi", 2800); }
    private double yakinlikSpawnUzakligi() { return plugin.getConfig().getDouble("zindan.yakinlik-spawn-uzakligi", 1000); }
    private int minKat() { return plugin.getConfig().getInt("zindan.min-kat", 3); }
    private int maxKat() { return plugin.getConfig().getInt("zindan.max-kat", 5); }
    private int katYuksekligiAyari() { return plugin.getConfig().getInt("zindan.kat-yuksekligi", 9); }
    private double canTemel() { return plugin.getConfig().getDouble("zindan.can-temel", 70); }
    private double canTierCarpani() { return plugin.getConfig().getDouble("zindan.can-tier-carpani", 1.45); }
    private double hasarTemel() { return plugin.getConfig().getDouble("zindan.hasar-temel", 7); }
    private double hasarTierCarpani() { return plugin.getConfig().getDouble("zindan.hasar-tier-carpani", 1.3); }
    private double lootXpTaban() { return plugin.getConfig().getDouble("zindan.loot-xp-carpani-taban", 1.0); }
    private double lootXpTier() { return plugin.getConfig().getDouble("zindan.loot-xp-carpani-tier", 0.3); }
    // Kullanıcı isteği (Ekim 2026): boss ödülü artık ÇOK daha düşük bir tavana çekildi - "bir boss'u
    // kesmenin bedeli 5-6 bin dolar falan olsun max, zor boss'tan kolay boss'a göre değişsin". Zindanın
    // kat sayısı (min-kat..max-kat) zorluk göstergesi olarak kullanılır: en kolay (min-kat) boss-odul-min,
    // en zor (max-kat) boss-odul-max civarında ödül verir (bkz. bossOlumIsle).
    private double bossOduluMin() { return plugin.getConfig().getDouble("zindan.boss-odul-min", 1800); }
    private double bossOduluMax() { return plugin.getConfig().getDouble("zindan.boss-odul-max", 6000); }
    // Boss öldükten sonra zindan 2-3 Minecraft günü (1 Minecraft günü = 20 dakika gerçek zaman) içinde
    // otomatik silinir (bkz. zindanBekcisi - silinmeZamani kontrolü).
    private double silinmeGunMin() { return plugin.getConfig().getDouble("zindan.silinme-gun-min", 2.0); }
    private double silinmeGunMax() { return plugin.getConfig().getDouble("zindan.silinme-gun-max", 3.0); }
    private static final long MINECRAFT_GUNU_MS = 1_200_000L; // 20 dakika
    private World dunya() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("zindan.dunya", "world"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    private void tick() {
        try {
            if (++bekciSayaci >= 20) { // ~20 sn'de bir
                bekciSayaci = 0;
                zindanBekcisi();
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Zindan tick() - bekçi hatası (atlanıyor)", e);
        }
        try {
            if (++yakinlikSayaci >= 60) { // ~60 sn'de bir
                yakinlikSayaci = 0;
                oyuncuYakinligiKontrolEt();
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Zindan tick() - yakınlık kontrolü hatası (atlanıyor)", e);
        }
        try {
            menzilBekcisi();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Zindan tick() - menzil bekçisi hatası (atlanıyor)", e);
        }
    }

    // ------------------------------------------------------------------ ÜRETİM
    /** merkezNoktasi null ise dünya spawn'ından min/max-spawn-uzakligi aralığı; verilmişse o noktanın ~0-400 blok civarı. */
    public Zindan otomatikUret(Location merkezNoktasi) {
        World w = dunya();
        Random rng = new Random();
        Location yuzeyNoktasi = rastgeleKonumBul(w, rng, merkezNoktasi);
        if (yuzeyNoktasi == null) return null;

        int katSayisi = minKat() + rng.nextInt(Math.max(1, maxKat() - minKat() + 1));
        int katYuksekligi = katYuksekligiAyari();
        ZindanInsaatYardimcisi.Sonuc yapi = ZindanInsaatYardimcisi.insaEt(yuzeyNoktasi, katSayisi, katYuksekligi, rng);

        Zindan z = new Zindan(UUID.randomUUID());
        z.isim = isimUret(rng);
        z.merkez = (yapi.girisNoktasi != null ? yapi.girisNoktasi : yuzeyNoktasi).clone();
        z.katSayisi = katSayisi;
        z.katYuksekligi = katYuksekligi;
        z.olusturulmaZamani = System.currentTimeMillis();
        String[] bossSecenekleri = {"TAS_YURUYEN", "KAN_CELLAT", "ISKELET_KRALI"};
        z.bossVaryanti = bossSecenekleri[rng.nextInt(bossSecenekleri.length)];
        zindanlar.put(z.id, z);

        for (int kat = 0; kat < yapi.katlar.size(); kat++) {
            ZindanInsaatYardimcisi.KatSonuc ks = yapi.katlar.get(kat);
            z.katMerkezleri.put(kat, ks.merkez.clone());
            int yariCap = (z.bossKati(kat) ? 15 : 10) + Math.min(5, kat); // ZindanInsaatYardimcisi#insaKat'taki formülle aynı
            z.katYariCaplari.put(kat, yariCap);
            if (z.bossKati(kat)) {
                bossDogur(z, kat, ks, rng);
                z.bossOduluSandigi = ks.oduluSandigi == null ? null : ks.oduluSandigi.clone();
            } else {
                for (Location sandikNoktasi : ks.sandikNoktalari) sandikDoldur(sandikNoktasi, kat + 1, rng);
                katTopla(z, kat, ks.mobNoktalari, ks.eliteNoktasi, rng);
            }
        }
        kaydet();
        plugin.getLogger().info("[Zindan] " + z.isim + " üretildi (" + katSayisi + " kat, boss: " + bossGorunenAd(z.bossVaryanti)
                + ") @ " + w.getName() + " " + yuzeyNoktasi.getBlockX() + "," + yuzeyNoktasi.getBlockZ());
        return z;
    }

    private String isimUret(Random rng) {
        return ISIM_ONEKLERI[rng.nextInt(ISIM_ONEKLERI.length)] + " " + ISIM_SONEKLERI[rng.nextInt(ISIM_SONEKLERI.length)]
                + " #" + (100 + rng.nextInt(900));
    }

    /**
     * Dünya spawn'ından (ya da merkezNoktasi verilmişse O noktadan) ve diğer zindanlardan uzak, kuru/
     * düzgün zeminli rastgele bir yüzey noktası arar - gerçek yapı bunun ALTINA inşa edilir (bkz.
     * ZindanInsaatYardimcisi). Vahşi Kale'deki rastgeleKonumBul ile aynı mantık.
     */
    private Location rastgeleKonumBul(World w, Random rng, Location merkezNoktasi) {
        boolean ozelMerkez = merkezNoktasi != null;
        Location merkez = ozelMerkez ? merkezNoktasi : w.getSpawnLocation();
        double minU = minSpawnUzakligi(), maxU = maxSpawnUzakligi();
        double araMin = zindanlarArasiMin();

        for (int deneme = 0; deneme < 60; deneme++) {
            double aci = rng.nextDouble() * Math.PI * 2;
            double uzaklik = ozelMerkez ? rng.nextDouble() * 400 : (minU + rng.nextDouble() * (maxU - minU));
            int x = merkez.getBlockX() + (int) Math.round(Math.cos(aci) * uzaklik);
            int z = merkez.getBlockZ() + (int) Math.round(Math.sin(aci) * uzaklik);

            boolean cokYakin = false;
            for (Zindan zz : zindanlar.values()) {
                if (zz.merkez == null || !w.equals(zz.merkez.getWorld())) continue;
                double dx = zz.merkez.getX() - x, dz = zz.merkez.getZ() - z;
                if (Math.sqrt(dx * dx + dz * dz) < araMin) { cokYakin = true; break; }
            }
            if (cokYakin) continue;

            w.getChunkAt(x >> 4, z >> 4).load();
            int yuksekBlok = w.getHighestBlockYAt(x, z);
            if (yuksekBlok < 50 || yuksekBlok > 200) continue;
            Material ust = w.getBlockAt(x, yuksekBlok, z).getType();
            if (ust == Material.WATER || ust == Material.LAVA || ust == Material.ICE || ust == Material.PACKED_ICE) continue;
            return new Location(w, x, yuksekBlok, z);
        }
        return null;
    }

    // ------------------------------------------------------------------ CANLI DOĞURMA
    private void katTopla(Zindan z, int kat, List<Location> noktalar, Location eliteNoktasi, Random rng) {
        int tier = kat + 1;
        for (Location n : noktalar) {
            canliDogur(z, kat, tier, rng.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON, n, rng, false);
        }
        if (eliteNoktasi != null) canliDogur(z, kat, tier, EntityType.VINDICATOR, eliteNoktasi, rng, false);
    }

    private LivingEntity canliDogur(Zindan z, int kat, int tier, EntityType tur, Location konum, Random rng, boolean bossMu) {
        LivingEntity canli = (LivingEntity) konum.getWorld().spawnEntity(konum, tur);
        canli.setPersistent(true);
        canli.setRemoveWhenFarAway(!bossMu); // normal/elite: uzaklaşınca despawn edebilir (hafiflik); boss: asla
        canli.getPersistentDataContainer().set(zindanKey, PersistentDataType.STRING, z.id.toString());
        canli.getPersistentDataContainer().set(katKey, PersistentDataType.INTEGER, kat);
        if (bossMu) canli.getPersistentDataContainer().set(bossMuKey, PersistentDataType.BYTE, (byte) 1);

        AttributeInstance canAttr = canli.getAttribute(Attribute.MAX_HEALTH);
        double can = canTemel() * Math.pow(canTierCarpani(), tier) * (tur == EntityType.VINDICATOR ? 1.3 : 1.0);
        if (canAttr != null) canAttr.setBaseValue(can);
        AttributeInstance hasarAttr = canli.getAttribute(Attribute.ATTACK_DAMAGE);
        double hasar = hasarTemel() * Math.pow(hasarTierCarpani(), tier);
        if (hasarAttr != null) hasarAttr.setBaseValue(hasar);

        if (!bossMu) {
            Varyant v = rastgeleVaryant(rng);
            varyantUygula(canli, v, tier);
            z.katCanlilari(kat).add(canli.getUniqueId());
        }
        if (canAttr != null) canli.setHealth(canAttr.getBaseValue());
        return canli;
    }

    private Varyant rastgeleVaryant(Random rng) {
        // Kullanıcı isteği: "moblar zor olsun" - NORMAL oranı düşürüldü, HIZLI/GUCLU daha sık.
        double zar = rng.nextDouble();
        if (zar < 0.35) return Varyant.NORMAL;
        return zar < 0.65 ? Varyant.HIZLI : Varyant.GUCLU;
    }

    /**
     * HIZLI: daha hızlı hareket eder ama daha az vurur. GUCLU: daha yavaştır ama daha çok vurur VE
     * vanilla menzilden biraz daha uzaktan da (ekstra) hasar verebilir - gerçek NMS reach değişikliği
     * yapmak yerine menzilBekcisi() ile simüle ediliyor (bkz. o metot).
     */
    private void varyantUygula(LivingEntity canli, Varyant v, int tier) {
        AttributeInstance hasarAttr = canli.getAttribute(Attribute.ATTACK_DAMAGE);
        double temelHasar = hasarAttr != null ? hasarAttr.getBaseValue() : 4;
        switch (v) {
            case HIZLI -> {
                canli.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, Math.min(2, 1 + tier / 3), false, false));
                if (hasarAttr != null) hasarAttr.setBaseValue(temelHasar * 0.7);
            }
            case GUCLU -> {
                canli.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 1, false, false));
                if (hasarAttr != null) hasarAttr.setBaseValue(temelHasar * 1.6);
                uzunMenzilliCanlilar.add(canli.getUniqueId());
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ BOSS
    private void bossDogur(Zindan z, int kat, ZindanInsaatYardimcisi.KatSonuc ks, Random rng) {
        if (ks.bossNoktasi == null) return;
        EntityType tur = switch (z.bossVaryanti) {
            case "TAS_YURUYEN" -> EntityType.RAVAGER;
            case "KAN_CELLAT" -> EntityType.VINDICATOR;
            default -> EntityType.SKELETON;
        };
        int tier = kat + 1;
        LivingEntity boss = canliDogur(z, kat, tier, tur, ks.bossNoktasi, rng, true);
        boss.setCustomName(ChatColor.DARK_RED + z.isim + " - " + bossGorunenAd(z.bossVaryanti));
        boss.setCustomNameVisible(true);

        AttributeInstance canAttr = boss.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance hasarAttr = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        double temelCan = canAttr != null ? canAttr.getBaseValue() : 60;
        double temelHasar = hasarAttr != null ? hasarAttr.getBaseValue() : 8;
        switch (z.bossVaryanti) {
            case "TAS_YURUYEN" -> {
                if (canAttr != null) canAttr.setBaseValue(temelCan * 2.2);
                if (hasarAttr != null) hasarAttr.setBaseValue(temelHasar * 1.3);
                AttributeInstance kb = boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                if (kb != null) kb.setBaseValue(1.0);
            }
            case "KAN_CELLAT" -> {
                if (canAttr != null) canAttr.setBaseValue(temelCan * 1.5);
                if (hasarAttr != null) hasarAttr.setBaseValue(temelHasar * 1.8);
                boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
            }
            case "ISKELET_KRALI" -> {
                if (canAttr != null) canAttr.setBaseValue(temelCan * 1.7);
                if (hasarAttr != null) hasarAttr.setBaseValue(temelHasar * 1.1);
                if (boss instanceof Skeleton sk && sk.getEquipment() != null) {
                    ItemStack yay = new ItemStack(Material.BOW);
                    yay.addUnsafeEnchantment(Enchantment.POWER, 2);
                    sk.getEquipment().setItemInMainHand(yay);
                }
                okYagmuruBaslat(z, boss.getUniqueId());
            }
        }
        if (canAttr != null) boss.setHealth(canAttr.getBaseValue());
        z.bossUuid = boss.getUniqueId();
    }

    /** İskelet Kralı için: yaşadığı sürece periyodik ok yağmuru - diğer varyantların yakın dövüş üstünlüğüne karşı ranged baskı. */
    private void okYagmuruBaslat(Zindan z, UUID bossUuid) {
        BukkitTask[] gorevRef = new BukkitTask[1];
        gorevRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Entity e = Bukkit.getEntity(bossUuid);
            if (!(e instanceof Skeleton sk) || sk.isDead() || z.temizlendi) {
                if (gorevRef[0] != null) gorevRef[0].cancel();
                return;
            }
            for (Player p : sk.getLocation().getNearbyPlayers(20)) {
                Vector yon = p.getLocation().toVector().subtract(sk.getLocation().toVector()).normalize();
                Arrow ok = sk.launchProjectile(Arrow.class, yon.multiply(1.6));
                ok.setDamage(4);
            }
        }, 100L, 100L);
    }

    private String bossGorunenAd(String varyant) {
        if (varyant == null) return "?";
        return switch (varyant) {
            case "TAS_YURUYEN" -> "Taş Yürüyen";
            case "KAN_CELLAT" -> "Kan Cellat";
            case "ISKELET_KRALI" -> "İskelet Kralı";
            default -> varyant;
        };
    }

    // ------------------------------------------------------------------ LOOT / SANDIK
    private void sandikDoldur(Location konum, int tier, Random rng) {
        BlockState durum = konum.getBlock().getState(false); // canlı state - snapshot değil (bkz. Vahşi Kale'deki ders)
        if (durum instanceof Chest sandik) {
            ZindanLootTablosu.doldur(sandik.getInventory(), tier, rng);
            sandik.update(true, false);
        }
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBossHasar(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        if (!le.getPersistentDataContainer().has(bossMuKey, PersistentDataType.BYTE)) return;
        String zId = le.getPersistentDataContainer().get(zindanKey, PersistentDataType.STRING);
        if (zId == null) return;
        Zindan z = zindanlar.get(UUID.fromString(zId));
        if (z == null) return;
        Player saldiran = saldiranOyuncu(event);
        if (saldiran == null) return;
        z.bossHasarKatkisi.merge(saldiran.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    private static Player saldiranOyuncu(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCanliOlum(EntityDeathEvent event) {
        LivingEntity le = event.getEntity();
        String zId = le.getPersistentDataContainer().get(zindanKey, PersistentDataType.STRING);
        if (zId == null) return;
        Zindan z = zindanlar.get(UUID.fromString(zId));
        uzunMenzilliCanlilar.remove(le.getUniqueId());
        uzunMenzilSonVurus.remove(le.getUniqueId());
        if (z == null) return;

        boolean bossMu = le.getPersistentDataContainer().has(bossMuKey, PersistentDataType.BYTE);
        Integer kat = le.getPersistentDataContainer().get(katKey, PersistentDataType.INTEGER);
        if (bossMu) {
            bossOlumIsle(z);
            return;
        }
        if (kat != null) z.katCanlilari(kat).remove(le.getUniqueId());
        int tier = (kat == null ? 0 : kat) + 1;
        Random rng = new Random();
        double carpan = lootXpTaban() + tier * lootXpTier();
        for (ItemStack it : event.getDrops()) {
            it.setAmount(Math.max(1, (int) Math.ceil(it.getAmount() * carpan)));
        }
        event.setDroppedExp((int) Math.ceil(event.getDroppedExp() * carpan));
        if (rng.nextDouble() < 0.10 + tier * 0.025) {
            event.getDrops().add(ZindanLootTablosu.tekRastgeleItem(tier, rng));
        }
    }

    private void bossOlumIsle(Zindan z) {
        if (z.temizlendi) return;
        z.temizlendi = true;
        // Kullanıcı isteği: boss düşünce zindan 2-3 Minecraft günü içinde otomatik silinsin (bkz. zindanBekcisi).
        double gun = silinmeGunMin() + Math.random() * (silinmeGunMax() - silinmeGunMin());
        z.silinmeZamani = System.currentTimeMillis() + Math.round(gun * MINECRAFT_GUNU_MS);
        kaydet();
        Bukkit.broadcastMessage(Mesaj.renk("&4&l" + z.isim + " ZİNDANI TEMİZLENDİ! &7(" + bossGorunenAd(z.bossVaryanti) + " düştü)"));

        if (z.bossOduluSandigi != null) sandikDoldur(z.bossOduluSandigi, z.katSayisi, new Random());

        double toplamHasar = z.bossHasarKatkisi.values().stream().mapToDouble(Double::doubleValue).sum();
        if (toplamHasar <= 0) return;
        // Zorluk göstergesi = kat sayısı (min-kat..max-kat arası) - kolay zindan boss-odul-min civarı,
        // zor (en çok kat) zindan boss-odul-max civarı ödül verir. Toplamda tüm oyuncular arası paylaşılır.
        double oran = Math.max(0, Math.min(1, (z.katSayisi - minKat()) / (double) Math.max(1, maxKat() - minKat())));
        double odulTabani = bossOduluMin() + oran * (bossOduluMax() - bossOduluMin());
        double toplamOdul = odulTabani * (0.9 + Math.random() * 0.2); // ±%10 rastgelelik
        for (Map.Entry<UUID, Double> e : z.bossHasarKatkisi.entrySet()) {
            double pay = Math.round(toplamOdul * (e.getValue() / toplamHasar) * 100.0) / 100.0;
            if (pay <= 0) continue;
            if (plugin.meslek().bankayaYatir(e.getKey(), pay)) {
                Player p = Bukkit.getPlayer(e.getKey());
                String isimOyuncu = p != null ? p.getName() : e.getKey().toString();
                plugin.meslek().ekonomiLog("zindan-boss-odul", isimOyuncu, z.isim + " boss ödülü +$" + pay);
                if (p != null) {
                    p.sendMessage(Mesaj.renk("&6" + z.isim + " &7boss'u düştü! Hasar katkına göre ödülün: &e$" + pay));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ BEKÇİ / MENZİL SİMÜLASYONU
    /**
     * Her katın canlı sayısını hedefte tutar (ölenlerin yerine yeni doğurur - vanilla bir mob farmı
     * gibi sürekli loot/XP kaynağı olsun diye), boss kaybolmuşsa (restart sonrası nadir bir durum)
     * yeniden doğurur. SADECE ZATEN YÜKLÜ (bir oyuncunun yakınında olduğu için chunk'ı açık) katlarda
     * çalışır - aksi halde restart sonrası boş görünen (ama chunk yüklenince asıl mobların hâlâ orada
     * olduğu) bir katı "boş" sanıp üstüne bir kat daha mob doğurarak çoğaltma (dupe) riski olurdu.
     */
    private void zindanBekcisi() {
        Random rng = new Random();
        Iterator<Zindan> disIterator = zindanlar.values().iterator();
        List<Zindan> silinecekler = new java.util.ArrayList<>();
        while (disIterator.hasNext()) {
            Zindan z = disIterator.next();
            // Kullanıcı isteği: boss'u kesilmiş zindan, kesilmesinden 2-3 Minecraft günü sonra otomatik
            // silinir (kayıt + fiziksel yapı) - bkz. bossOlumIsle (silinmeZamani hesaplanır) ve
            // zindaniFizikselSil (gerçek blok temizliği).
            if (z.temizlendi && z.silinmeZamani > 0 && System.currentTimeMillis() >= z.silinmeZamani) {
                silinecekler.add(z);
            }
        }
        for (Zindan z : silinecekler) {
            zindaniFizikselSil(z);
        }

        for (Zindan z : zindanlar.values()) {
            if (z.temizlendi || z.merkez == null || z.merkez.getWorld() == null) continue;
            for (int kat = 0; kat < z.katSayisi - 1; kat++) {
                Location merkez = z.katMerkezleri.get(kat);
                Integer yariCap = z.katYariCaplari.get(kat);
                if (merkez == null || merkez.getWorld() == null || yariCap == null) continue;
                if (!merkez.getWorld().isChunkLoaded(merkez.getBlockX() >> 4, merkez.getBlockZ() >> 4)) continue;

                Set<UUID> canlilar = z.katCanlilari(kat);
                if (canlilar.isEmpty()) gerceklesenCanlilariYenidenBul(z, kat);
                canlilar.removeIf(uuid -> Bukkit.getEntity(uuid) == null);

                int tier = kat + 1;
                int hedefSayi = 3 + (int) Math.round(tier * 1.4); // ZindanInsaatYardimcisi#insaKat'taki mobSayisi formülüyle aynı
                int eksik = hedefSayi - canlilar.size();
                for (int i = 0; i < eksik; i++) {
                    double aci = rng.nextDouble() * Math.PI * 2;
                    double uzaklik = 1 + rng.nextDouble() * Math.max(1, yariCap - 2);
                    Location nokta = merkez.clone().add(Math.cos(aci) * uzaklik, 0, Math.sin(aci) * uzaklik);
                    canliDogur(z, kat, tier, rng.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON, nokta, rng, false);
                }
            }

            if (z.bossUuid != null && Bukkit.getEntity(z.bossUuid) == null) {
                Location bossNoktasi = z.katMerkezleri.get(z.katSayisi - 1);
                if (bossNoktasi != null && bossNoktasi.getWorld() != null
                        && bossNoktasi.getWorld().isChunkLoaded(bossNoktasi.getBlockX() >> 4, bossNoktasi.getBlockZ() >> 4)) {
                    ZindanInsaatYardimcisi.KatSonuc sahte = new ZindanInsaatYardimcisi.KatSonuc();
                    sahte.bossNoktasi = bossNoktasi;
                    bossDogur(z, z.katSayisi - 1, sahte, rng);
                    kaydet();
                }
            }
        }
    }

    /**
     * Kullanıcı isteği: boss düşmüş (temizlenmiş) bir zindan 2-3 Minecraft günü sonra ortadan kalksın.
     * Kalan canlıları siler, her katın oda+odacık+merdiven alanını katı taşla (COBBLED_DEEPSLATE) geri
     * doldurarak fiziksel yapıyı da kapatır (tam orijinal doku değil ama boşluk/oyuk kalmaz - "silinsin"
     * isteğiyle tutarlı), kaydı zindanlar.yml'den düşürür. min-aktif-zindan-sayisi altına düşünce
     * otomatikKontrolEt() zaten yeni bir zindan üretecektir.
     */
    private void zindaniFizikselSil(Zindan z) {
        for (Set<UUID> set : z.katCanlilari.values()) {
            for (UUID id : set) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        }
        if (z.bossUuid != null) { Entity e = Bukkit.getEntity(z.bossUuid); if (e != null) e.remove(); }

        for (Map.Entry<Integer, Location> e : z.katMerkezleri.entrySet()) {
            Location merkez = e.getValue();
            Integer yariCap = z.katYariCaplari.get(e.getKey());
            if (merkez == null || merkez.getWorld() == null || yariCap == null) continue;
            World w = merkez.getWorld();
            int cx = merkez.getBlockX();
            int cz = merkez.getBlockZ();
            int tabanY = merkez.getBlockY() - 1; // ks.merkez = tabanY+1 (bkz. ZindanInsaatYardimcisi#insaKat)
            int genislik = yariCap + 5; // ana oda + odacıkların (yariCap+3+2) tamamını kapsar
            int minX = cx - genislik, maxX = cx + genislik;
            int minZ = cz - genislik, maxZ = cz + genislik;
            int minY = tabanY - 1, maxY = tabanY + z.katYuksekligi;
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) continue; // chunk yüklü değilse dokunma, bir dahaki bekçi turunda tekrar denenir
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z2 = minZ; z2 <= maxZ; z2++) {
                        w.getBlockAt(x, y, z2).setType(Material.COBBLED_DEEPSLATE);
                    }
                }
            }
        }
        // Not: eskiden burada yüzeye çıkan giriş şaftını da kapatan bir adım vardı - artık zindanlar
        // yüzeyle hiç bağlantılı inşa edilmiyor (bkz. ZindanInsaatYardimcisi#insaEt, kullanıcı isteği:
        // "trial chamber'lar gibi izole") - z.merkez artık ilk katın kendi merkezi, yukarıdaki kat
        // döngüsü (her katın oda+odacıklarını dolduran) onu da zaten kapsıyor, ekstra adıma gerek yok.

        zindanlar.remove(z.id);
        kaydet();
        plugin.getLogger().info("[Zindan] " + z.isim + " (boss düşeli " + silinmeGunMin() + "-" + silinmeGunMax()
                + " Minecraft günü geçti) otomatik silindi.");
    }

    /** Restart sonrası (ya da başka bir sebeple boş görünen) bir katta gerçekten hâlâ yaşayan, bize ait
     *  canlıları PDC etiketinden tarayıp bulur - bekçinin "boş" sanıp dupe etmesini önler. */
    private void gerceklesenCanlilariYenidenBul(Zindan z, int kat) {
        Location merkez = z.katMerkezleri.get(kat);
        if (merkez == null || merkez.getWorld() == null) return;
        Set<UUID> set = z.katCanlilari(kat);
        for (Entity e : merkez.getWorld().getNearbyEntities(merkez, 16, 10, 16)) {
            if (!(e instanceof LivingEntity le)) continue;
            String zId = le.getPersistentDataContainer().get(zindanKey, PersistentDataType.STRING);
            if (zId == null || !zId.equals(z.id.toString())) continue;
            Integer k = le.getPersistentDataContainer().get(katKey, PersistentDataType.INTEGER);
            if (k == null || k != kat) continue;
            if (le.getPersistentDataContainer().has(bossMuKey, PersistentDataType.BYTE)) continue;
            set.add(le.getUniqueId());
            if (le.hasPotionEffect(PotionEffectType.SLOWNESS)) uzunMenzilliCanlilar.add(le.getUniqueId());
        }
    }

    /**
     * GUCLU varyantlı canlılar vanilla menzilin (~2.1 blok) biraz dışından da (3.2 bloka kadar) ekstra
     * bir "erişim" hasarı verebilir - gerçek NMS entity reach değişikliği yerine bu basit simülasyon
     * kullanılıyor (bkz. varyantUygula). Her canlı için en fazla ~1.5 saniyede bir vurabilir.
     */
    private void menzilBekcisi() {
        long simdi = System.currentTimeMillis();
        Iterator<UUID> it = uzunMenzilliCanlilar.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity canli) || canli.isDead()) {
                it.remove();
                uzunMenzilSonVurus.remove(id);
                continue;
            }
            Long son = uzunMenzilSonVurus.get(id);
            if (son != null && simdi - son < 1500) continue;
            for (Player p : canli.getLocation().getNearbyPlayers(3.2)) {
                double d = p.getLocation().distance(canli.getLocation());
                if (d <= 2.1 || d > 3.2) continue;
                AttributeInstance hasarAttr = canli.getAttribute(Attribute.ATTACK_DAMAGE);
                double hasar = (hasarAttr != null ? hasarAttr.getBaseValue() : 4) * 0.6;
                p.damage(hasar, canli);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
                uzunMenzilSonVurus.put(id, simdi);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ OYUNCU YAKINLIĞI GARANTİSİ
    /**
     * Vahşi Kale'deki oyuncuYakinligiKontrolEt() ile AYNI mantık: bir oyuncu haritada spawn'dan çok
     * uzağa (keşif) gidip çevresinde hiç zindan yoksa, biraz ötesine (yakinlik-spawn-uzakligi) yeni
     * bir zindan üretir - böylece keşif her zaman erişilebilir içerik bulur. Bir çalıştırmada en fazla
     * 1 zindan üretir (sunucuyu yormamak için).
     */
    public void oyuncuYakinligiKontrolEt() {
        World w = dunya();
        double esik = yakinlikEsigi();
        double spawnUzakligi = yakinlikSpawnUzakligi();
        Random rng = new Random();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            if (!w.equals(loc.getWorld())) continue;
            double enYakin = Double.MAX_VALUE;
            for (Zindan z : zindanlar.values()) {
                if (z.merkez == null || !w.equals(z.merkez.getWorld())) continue;
                double dx = z.merkez.getX() - loc.getX();
                double dz = z.merkez.getZ() - loc.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < enYakin) enYakin = d;
            }
            if (enYakin <= esik) continue;
            double aci = rng.nextDouble() * Math.PI * 2;
            Location merkezNoktasi = new Location(w, loc.getX() + Math.cos(aci) * spawnUzakligi, 0, loc.getZ() + Math.sin(aci) * spawnUzakligi);
            Zindan yeni = otomatikUret(merkezNoktasi);
            if (yeni != null) {
                plugin.getLogger().info("[Zindan] Yakınlık kontrolü: " + p.getName() + " en yakın zindandan ~"
                        + Math.round(enYakin) + " blok uzaktaydı -> " + yeni.isim + " o bölgeye üretildi.");
            }
            return;
        }
    }

    private int aktifZindanSayisi() {
        int sayi = 0;
        for (Zindan z : zindanlar.values()) if (!z.temizlendi) sayi++;
        return sayi;
    }

    /** Açılışta bir kere (bkz. ArenaLigi onEnable). */
    public void otomatikIlkDoldurma() {
        int eksik = minAktifZindan() - aktifZindanSayisi();
        for (int i = 0; i < eksik; i++) {
            final int sira = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> otomatikUret(null), 120L + sira * 100L);
        }
    }

    /** Periyodik (bkz. ArenaLigi onEnable): temizlenen zindanların yerine toplam sayı asgarinin altına düşerse yeni üretir. */
    public void otomatikKontrolEt() {
        int eksik = minAktifZindan() - aktifZindanSayisi();
        for (int i = 0; i < eksik; i++) {
            final int sira = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> otomatikUret(null), 40L + sira * 100L);
        }
    }

    // ------------------------------------------------------------------ KOMUT
    /** Tab-complete için zindan isimleri (bkz. ArenaKomut#onTabComplete). */
    public List<String> isimler() {
        List<String> liste = new java.util.ArrayList<>();
        for (Zindan z : zindanlar.values()) liste.add(z.isim);
        return liste;
    }

    public void komut(CommandSender s, String[] args) {
        if (args.length < 3) { kullanim(s); return; }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "uret" -> uretKomut(s);
            case "sil" -> silKomut(s, args);
            case "liste" -> listeKomut(s);
            case "bilgi" -> bilgiKomut(s, args);
            case "git" -> gitKomut(s, args);
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan <uret|sil <isim>|liste|bilgi <isim>|git <isim>>"));
    }

    /** /arena admin zindan git <isim>: admin, zindanın giriş noktasına ışınlanır - liste()'deki
     *  tıklanabilir koordinat butonu da bunu çağırır (Vahşi Kale'deki /vahsikale git ile aynı desen). */
    private void gitKomut(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        if (args.length < 4) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan git <isim>")); return; }
        String aranan = String.join(" ", java.util.Arrays.asList(args).subList(3, args.length));
        Zindan hedefZindan = null;
        for (Zindan z : zindanlar.values()) if (z.isim.equalsIgnoreCase(aranan)) { hedefZindan = z; break; }
        if (hedefZindan == null || hedefZindan.merkez == null || hedefZindan.merkez.getWorld() == null) {
            p.sendMessage(Mesaj.renk("&cBöyle bir zindan yok ya da geçerli bir konumu yok."));
            return;
        }
        p.teleport(hedefZindan.merkez);
        p.sendMessage(Mesaj.renk("&a" + hedefZindan.isim + " zindanına ışınlandın."));
    }

    private void uretKomut(CommandSender s) {
        Zindan z = otomatikUret(null);
        if (z == null) { s.sendMessage(Mesaj.renk("&cUygun/güvenli bir konum bulunamadı (deneme limiti doldu). Tekrar dene.")); return; }
        s.sendMessage(Mesaj.renk("&a" + z.isim + " &7üretildi (" + z.katSayisi + " kat, boss: " + bossGorunenAd(z.bossVaryanti) + ") - &f"
                + z.merkez.getWorld().getName() + " " + z.merkez.getBlockX() + ", " + z.merkez.getBlockZ()));
    }

    private void silKomut(CommandSender s, String[] args) {
        if (args.length < 4) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan sil <isim>")); return; }
        Zindan hedef = null;
        for (Zindan z : zindanlar.values()) if (z.isim.equalsIgnoreCase(args[3])) { hedef = z; break; }
        if (hedef == null) { s.sendMessage(Mesaj.renk("&cBöyle bir zindan yok.")); return; }
        for (Set<UUID> set : hedef.katCanlilari.values()) {
            for (UUID id : set) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        }
        if (hedef.bossUuid != null) { Entity e = Bukkit.getEntity(hedef.bossUuid); if (e != null) e.remove(); }
        zindanlar.remove(hedef.id);
        kaydet();
        s.sendMessage(Mesaj.renk("&a" + hedef.isim + " silindi. &7(Fiziksel yapı yerinde kalır - sadece kayıt/canlılar silindi.)"));
    }

    private void listeKomut(CommandSender s) {
        if (zindanlar.isEmpty()) { s.sendMessage(Mesaj.renk("&7Hiç zindan yok. &e/arena admin zindan uret")); return; }
        s.sendMessage(Mesaj.renk("&6&l--- ZİNDANLAR ---"));
        for (Zindan z : zindanlar.values()) {
            String durum = z.temizlendi ? "&cTemizlendi" : "&aAktif";
            net.kyori.adventure.text.Component satir = net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize(Mesaj.renk("&e" + z.isim + " &7(" + z.katSayisi + " kat, boss: " + bossGorunenAd(z.bossVaryanti) + ") - " + durum + " &7| ")))
                    .append(koordinatButonu(z))
                    .build();
            s.sendMessage(satir);
        }
    }

    /** listeKomut()'ta her zindan için gösterilen, tıklanınca o zindana ışınlayan koordinat bileşeni
     *  (/arena admin zindan git <isim> komutunu tetikler) - Vahşi Kale'deki koordinatButonu() ile aynı desen. */
    private net.kyori.adventure.text.Component koordinatButonu(Zindan z) {
        if (z.merkez == null || z.merkez.getWorld() == null) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(Mesaj.renk("&8[konum yok]"));
        }
        String koordMetni = z.merkez.getWorld().getName() + " " + z.merkez.getBlockX() + ", " + z.merkez.getBlockY() + ", " + z.merkez.getBlockZ();
        net.kyori.adventure.text.Component ipucu = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(Mesaj.renk("&7Işınlanmak için tıkla: &e/arena admin zindan git " + z.isim));
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(Mesaj.renk("&b&n[" + koordMetni + "]"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/arena admin zindan git " + z.isim))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(ipucu));
    }

    private void bilgiKomut(CommandSender s, String[] args) {
        if (args.length < 4) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan bilgi <isim>")); return; }
        for (Zindan z : zindanlar.values()) {
            if (!z.isim.equalsIgnoreCase(args[3]) || z.merkez == null || z.merkez.getWorld() == null) continue;
            s.sendMessage(Mesaj.renk("&6" + z.isim + " &7- " + z.katSayisi + " kat, boss: " + bossGorunenAd(z.bossVaryanti) + ", "
                    + (z.temizlendi ? "&cTemizlendi" : "&aAktif") + " &7- &f"
                    + z.merkez.getWorld().getName() + " " + z.merkez.getBlockX() + ", " + z.merkez.getBlockY() + ", " + z.merkez.getBlockZ()));
            return;
        }
        s.sendMessage(Mesaj.renk("&cBöyle bir zindan yok."));
    }

    // ------------------------------------------------------------------ YAML
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zindan z : zindanlar.values()) {
            String yol = "zindanlar." + z.id;
            y.set(yol + ".isim", z.isim);
            y.set(yol + ".merkez", Konum.yaz(z.merkez));
            y.set(yol + ".kat-sayisi", z.katSayisi);
            y.set(yol + ".kat-yuksekligi", z.katYuksekligi);
            y.set(yol + ".boss-varyanti", z.bossVaryanti);
            y.set(yol + ".temizlendi", z.temizlendi);
            y.set(yol + ".olusturulma-zamani", z.olusturulmaZamani);
            y.set(yol + ".silinme-zamani", z.silinmeZamani);
            y.set(yol + ".boss-uuid", z.bossUuid == null ? null : z.bossUuid.toString());
            y.set(yol + ".boss-odulu-sandigi", Konum.yaz(z.bossOduluSandigi));
            for (Map.Entry<Integer, Location> e : z.katMerkezleri.entrySet()) {
                y.set(yol + ".katlar." + e.getKey() + ".merkez", Konum.yaz(e.getValue()));
                Integer yr = z.katYariCaplari.get(e.getKey());
                y.set(yol + ".katlar." + e.getKey() + ".yaricap", yr == null ? 6 : yr);
            }
        }
        return y;
    }

    private void kaydet() { dosya.kaydet(); }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    private void yukle() {
        YamlConfiguration y = dosya.oku();
        ConfigurationSection zs = y.getConfigurationSection("zindanlar");
        if (zs == null) return;
        for (String idStr : zs.getKeys(false)) {
            ConfigurationSection c = zs.getConfigurationSection(idStr);
            if (c == null) continue;
            UUID id;
            try { id = UUID.fromString(idStr); } catch (IllegalArgumentException e) { continue; }
            Zindan z = new Zindan(id);
            z.isim = c.getString("isim", "Zindan");
            z.merkez = Konum.oku(c.getString("merkez"));
            z.katSayisi = c.getInt("kat-sayisi", 3);
            z.katYuksekligi = c.getInt("kat-yuksekligi", 9);
            z.bossVaryanti = c.getString("boss-varyanti", "TAS_YURUYEN");
            z.temizlendi = c.getBoolean("temizlendi", false);
            z.olusturulmaZamani = c.getLong("olusturulma-zamani", System.currentTimeMillis());
            z.silinmeZamani = c.getLong("silinme-zamani", 0L);
            String bossUuidStr = c.getString("boss-uuid");
            z.bossUuid = bossUuidStr == null ? null : UUID.fromString(bossUuidStr);
            z.bossOduluSandigi = Konum.oku(c.getString("boss-odulu-sandigi"));
            if (z.merkez == null) {
                plugin.getLogger().warning("'" + z.isim + "' zindanının dünyası yüklü değil, şimdilik atlanıyor.");
                continue;
            }
            ConfigurationSection katlar = c.getConfigurationSection("katlar");
            if (katlar != null) {
                for (String katStr : katlar.getKeys(false)) {
                    int kat;
                    try { kat = Integer.parseInt(katStr); } catch (NumberFormatException e) { continue; }
                    ConfigurationSection kc = katlar.getConfigurationSection(katStr);
                    if (kc == null) continue;
                    Location merkezKat = Konum.oku(kc.getString("merkez"));
                    if (merkezKat != null) z.katMerkezleri.put(kat, merkezKat);
                    z.katYariCaplari.put(kat, kc.getInt("yaricap", 6));
                }
            }
            zindanlar.put(z.id, z);
        }
        plugin.getLogger().info("[Zindan] " + zindanlar.size() + " zindan yüklendi.");
    }
}
