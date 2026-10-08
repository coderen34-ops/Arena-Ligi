package me.arenaligi.veri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import me.arenaligi.ArenaLigi;

/**
 * Güvenli YAML dosyası (Klan-Sistemi'ndeki kayıt yönteminin aynısı):
 * - Kayıt istekleri 1 saniye içinde birleştirilir; YAML ana thread'de üretilir, dosya arka planda yazılır.
 * - Yazmadan önce mevcut dosya .bak olarak yedeklenir; yeni içerik önce .tmp dosyasına yazılıp
 *   atomik olarak yerine taşınır (yarım kalan yazım dosyayı bozmaz).
 * - Ana dosya okunamazsa yedekten yüklenir; bozuk dosya ilk kayıtta ayrıca saklanır.
 */
public class YamlDosya {

    private final ArenaLigi plugin;
    private final File dosya;
    private final Supplier<YamlConfiguration> uretici;
    private boolean kayitPlanlandi = false;
    private volatile boolean anaDosyaBozuk = false;

    public YamlDosya(ArenaLigi plugin, String ad, Supplier<YamlConfiguration> uretici) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), ad);
        this.uretici = uretici;
    }

    /** Dosyayı okur (yoksa boş). Ana dosya bozuksa yedekten. */
    public YamlConfiguration oku() {
        if (!dosya.exists()) return new YamlConfiguration();
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(dosya);
            return y;
        } catch (IOException | InvalidConfigurationException e) {
            anaDosyaBozuk = true;
            File yedek = new File(dosya.getParentFile(), dosya.getName() + ".bak");
            plugin.getLogger().log(Level.SEVERE, dosya.getName() + " okunamadı! Yedekten yükleniyor.", e);
            YamlConfiguration yy = new YamlConfiguration();
            try {
                if (yedek.exists()) yy.load(yedek);
            } catch (IOException | InvalidConfigurationException e2) {
                plugin.getLogger().log(Level.SEVERE, yedek.getName() + " de okunamadı!", e2);
            }
            return yy;
        }
    }

    /** Birleştirilmiş, arka planda kayıt. */
    public void kaydet() {
        if (!plugin.isEnabled() || plugin.kapaniyor()) {
            hemenKaydet();
            return;
        }
        if (kayitPlanlandi) return;
        kayitPlanlandi = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kayitPlanlandi = false;
            String icerik = uretici.get().saveToString();
            plugin.arkaPlanda(() -> diskeYaz(icerik));
        }, 20L);
    }

    /** Senkron ve eksiksiz kayıt (kapanışta ya da kritik anlarda, örn. envanter yedeği). */
    public void hemenKaydet() {
        diskeYaz(uretici.get().saveToString());
    }

    private synchronized void diskeYaz(String icerik) {
        try {
            Files.createDirectories(dosya.getParentFile().toPath());
            if (anaDosyaBozuk) {
                if (dosya.exists()) {
                    Files.move(dosya.toPath(), new File(dosya.getParentFile(), dosya.getName() + ".bozuk-" + System.currentTimeMillis()).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                anaDosyaBozuk = false;
            } else if (dosya.exists()) {
                Files.copy(dosya.toPath(), new File(dosya.getParentFile(), dosya.getName() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Path gecici = new File(dosya.getParentFile(), dosya.getName() + ".tmp").toPath();
            Files.writeString(gecici, icerik, StandardCharsets.UTF_8);
            try {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, dosya.getName() + " kaydedilemedi!", e);
        }
    }
}
