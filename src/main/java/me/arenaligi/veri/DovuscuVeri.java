package me.arenaligi.veri;

import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import me.arenaligi.ArenaLigi;
import me.arenaligi.model.Dovuscu;

/** Dövüşçüler, lakaplar, lig/puan ve istatistikler: data.yml */
public class DovuscuVeri {

    private final ArenaLigi plugin;
    private final YamlDosya dosya;

    public DovuscuVeri(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "data.yml", this::olustur);
    }

    public void kaydet() { dosya.kaydet(); }
    public void hemenKaydet() { dosya.hemenKaydet(); }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Dovuscu d : plugin.dovusculer().hepsi()) {
            String yol = "dovusculer." + d.uuid;
            y.set(yol + ".isim", d.isim);
            y.set(yol + ".lakap", d.lakap);
            y.set(yol + ".puan", d.puan);
            y.set(yol + ".lig", d.lig);
            y.set(yol + ".galibiyet", d.galibiyet);
            y.set(yol + ".maglubiyet", d.maglubiyet);
            y.set(yol + ".beraberlik", d.beraberlik);
            y.set(yol + ".seri", d.seri);
            y.set(yol + ".en-iyi-seri", d.enIyiSeri);
            y.set(yol + ".en-yuksek-puan", d.enYuksekPuan);
            y.set(yol + ".kayit", d.kayitTarihi);
        }
        return y;
    }

    public void yukle() {
        YamlConfiguration y = dosya.oku();
        ConfigurationSection bolum = y.getConfigurationSection("dovusculer");
        if (bolum == null) return;
        for (String k : bolum.getKeys(false)) {
            ConfigurationSection c = bolum.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Dovuscu d = new Dovuscu(UUID.fromString(k), c.getString("isim", "?"), c.getString("lakap", "?"), c.getLong("kayit"));
                d.puan = Math.max(0, c.getInt("puan"));
                d.lig = Math.max(0, c.getInt("lig"));
                d.galibiyet = c.getInt("galibiyet");
                d.maglubiyet = c.getInt("maglubiyet");
                d.beraberlik = c.getInt("beraberlik");
                d.seri = c.getInt("seri");
                d.enIyiSeri = c.getInt("en-iyi-seri");
                d.enYuksekPuan = c.getInt("en-yuksek-puan");
                plugin.dovusculer().ekle(d);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[Veri] '" + k + "' dövüşçüsü yüklenemedi: " + e.getMessage());
            }
        }
    }
}
