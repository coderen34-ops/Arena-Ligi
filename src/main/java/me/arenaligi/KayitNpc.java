package me.arenaligi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.arenaligi.model.Dovuscu;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Arena Kayıt NPC'si: kayıtsız oyuncu lakabını sohbete yazarak dövüşçü olur (ücretsiz),
 * kayıtlı oyuncu kart bilgisini görür. NPC: AI'sı kapalı, ölümsüz köylü (PDC ile işaretli).
 */
public class KayitNpc implements Listener {

    private final ArenaLigi plugin;
    private final NamespacedKey npcKey;
    private final Map<UUID, Long> lakapBekleyen = new HashMap<>(); // oyuncu -> istek zamanı
    private static final long BEKLEME_MS = 60_000L;

    public KayitNpc(ArenaLigi plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "kayit_npc");
    }

    private Mesaj m() { return plugin.mesaj(); }

    public NamespacedKey npcKey() { return npcKey; }

    // ------------------------------------------------------------------ KUR / SİL
    public void kur(Player p) {
        Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCollidable(false);
        v.setProfession(Villager.Profession.WEAPONSMITH);
        v.setCustomName(m().metin("npc-kayit-isim", "&c&lArena Kayıt"));
        v.setCustomNameVisible(true);
        v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        m().gonder(p, "npc-kuruldu", "&aArena Kayıt NPC'si kuruldu.");
    }

    public void sil(Player p) {
        int silinen = 0;
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) { e.remove(); silinen++; }
        }
        m().gonder(p, "npc-silindi", "&e{sayi} NPC silindi.", "sayi", silinen);
    }

    // ------------------------------------------------------------------ ETKİLEŞİM
    @EventHandler(priority = EventPriority.LOW)
    public void onNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        if (!p.hasPermission("arena.kullan")) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); return; }
        Dovuscu d = plugin.dovusculer().get(p.getUniqueId());
        if (d != null) {
            kartGoster(p, d);
            return;
        }
        String engel = plugin.kuyruk().engel(p);
        if (engel != null) { p.sendMessage(m().onek() + engel); return; }
        lakapBekleyen.put(p.getUniqueId(), System.currentTimeMillis());
        m().gonder(p, "lakap-iste", "&6Arenaya hoş geldin! &fDövüşçü lakabını sohbete yaz &7(örn. Karın Deşen Albert, {min}-{max} karakter). &7İptal: &fiptal",
                "min", plugin.getConfig().getInt("lakap.min", 3), "max", plugin.getConfig().getInt("lakap.max", 24));
    }

    /** Lakap sohbetten alınır; işlem ana thread'de yapılır. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        Long zaman = lakapBekleyen.get(p.getUniqueId());
        if (zaman == null) return;
        event.setCancelled(true);
        String yazi = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> lakapIsle(p, yazi));
    }

    private void lakapIsle(Player p, String yazi) {
        Long zaman = lakapBekleyen.remove(p.getUniqueId());
        if (zaman == null || !p.isOnline()) return;
        if (System.currentTimeMillis() - zaman > BEKLEME_MS) {
            m().gonder(p, "lakap-zaman-asimi", "&cSüre doldu. Tekrar kayıt NPC'sine tıklayın.");
            return;
        }
        if (yazi.equalsIgnoreCase("iptal")) { m().gonder(p, "iptal", "&eİptal edildi."); return; }
        if (plugin.dovusculer().get(p.getUniqueId()) != null) return;
        String engel = plugin.kuyruk().engel(p);
        if (engel != null) { p.sendMessage(m().onek() + engel); return; }
        String hata = plugin.dovusculer().lakapHatasi(yazi, p.getUniqueId());
        if (hata != null) {
            p.sendMessage(m().onek() + hata + " " + m().metin("lakap-tekrar", "&7Tekrar yazın ya da 'iptal'."));
            lakapBekleyen.put(p.getUniqueId(), System.currentTimeMillis());
            return;
        }
        Dovuscu d = plugin.dovusculer().kaydet(p, yazi);
        m().gonder(p, "kayit-tamam", "&a&l\"{lakap}\" &aartık bir arena dövüşçüsü! &7Lig: {lig}&7. Maç sırasına girmek için: &f/arena sira",
                "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen());
        p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 0.8f, 1.0f);
    }

    private void kartGoster(Player p, Dovuscu d) {
        m().gonder(p, "kart", "&6\"{lakap}\" &7| Lig: {lig} &7| Puan: &f{puan} &7| G/M/B: &a{g}&7/&c{mg}&7/&e{b}",
                "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen(), "puan", d.puan, "g", d.galibiyet, "mg", d.maglubiyet, "b", d.beraberlik);
        m().gonder(p, "kart-ipucu", "&7Maç sırası: &f/arena sira &7| İstatistik: &f/arena istatistik &7| Sıralama: &f/arena siralama");
    }

    @EventHandler(ignoreCancelled = true)
    public void onHasar(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lakapBekleyen.remove(event.getPlayer().getUniqueId());
    }
}
