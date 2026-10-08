package me.arenaligi.kuyruk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.model.Dovuscu;

/**
 * Maç kuyruğu (/arena sira, /arena ayril). Bellekte tutulur: sunucu yeniden başlarsa kuyruk boşalır.
 * Eşleştirme ve otomatik maç döngüsü 3. aşamada bu kuyruğu kullanır.
 */
public class Kuyruk implements Listener {

    private final ArenaLigi plugin;
    private final Map<UUID, Long> sira = new LinkedHashMap<>(); // dövüşçü -> kuyruğa girdiği an

    public Kuyruk(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    public boolean siradaMi(UUID u) { return sira.containsKey(u); }
    public int boyut() { return sira.size(); }

    /** Kuyruktakiler, giriş sırasına göre. */
    public List<UUID> siradakiler() { return new ArrayList<>(sira.keySet()); }

    public long girisZamani(UUID u) { return sira.getOrDefault(u, 0L); }

    /**
     * Oyuncunun kayıt olmasına / kuyruğa girmesine / maça alınmasına engel varsa açıklaması, yoksa null.
     * Sağlıktan muaf oyuncu (örn. klan savaşında ya da başka bir maçta) meşgul sayılır.
     */
    public String engel(Player p) {
        UUID u = p.getUniqueId();
        if (plugin.meslek().hapisteMi(u)) return m().metin("engel-hapis", "&cHapisteyken arenaya katılamazsınız.");
        if (plugin.meslek().durusmadaMi(u)) return m().metin("engel-durusma", "&cDuruşmadayken arenaya katılamazsınız.");
        if (plugin.meslek().agirYaraliMi(u)) return m().metin("engel-yarali", "&cAğır yaralıyken arenaya katılamazsınız.");
        if (plugin.meslek().saglikMuafMi(u)) return m().metin("engel-mesgul", "&cŞu an başka bir dövüştesiniz (klan savaşı ya da maç).");
        if (p.isDead()) return m().metin("engel-olu", "&cŞu an arenaya katılamazsınız.");
        return null;
    }

    public void gir(Player p) {
        Dovuscu d = plugin.dovusculer().get(p.getUniqueId());
        if (d == null) {
            m().gonder(p, "kayitli-degil", "&cÖnce Arena Kayıt NPC'sinden dövüşçü olarak kayıt olmalısınız.");
            return;
        }
        if (sira.containsKey(p.getUniqueId())) {
            m().gonder(p, "zaten-sirada", "&eZaten maç sırasındasınız. &7(Sırada {sayi} kişi) Çıkmak için: /arena ayril", "sayi", sira.size());
            return;
        }
        String engel = engel(p);
        if (engel != null) {
            p.sendMessage(m().onek() + engel);
            return;
        }
        sira.put(p.getUniqueId(), System.currentTimeMillis());
        m().gonder(p, "siraya-girdi", "&a\"{lakap}\" maç sırasına girdi! &7(Lig: {lig}&7, sırada {sayi} kişi) Çıkmak için: /arena ayril",
                "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen(), "sayi", sira.size());
    }

    public void ayril(Player p) {
        if (sira.remove(p.getUniqueId()) == null) {
            m().gonder(p, "sirada-degil", "&cMaç sırasında değilsiniz.");
            return;
        }
        m().gonder(p, "siradan-cikti", "&eMaç sırasından çıktınız.");
    }

    /** Sessizce çıkarır (oyundan çıkma, maça alınma vb.). */
    public boolean cikar(UUID u) { return sira.remove(u) != null; }

    /** Sıradakilerden artık uygun olmayanları (hapis, duruşma, yaralı, çevrimdışı) çıkarır ve bildirir. */
    public void temizle() {
        for (UUID u : new ArrayList<>(sira.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { sira.remove(u); continue; }
            String engel = engel(p);
            if (engel != null) {
                sira.remove(u);
                p.sendMessage(m().onek() + engel + " " + m().metin("siradan-dusuruldu", "&7Maç sırasından çıkarıldınız."));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sira.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.dovusculer().isimGuncelle(event.getPlayer());
    }
}
