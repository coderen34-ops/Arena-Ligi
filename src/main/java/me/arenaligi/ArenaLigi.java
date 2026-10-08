package me.arenaligi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import me.arenaligi.komut.ArenaKomut;
import me.arenaligi.kuyruk.Kuyruk;
import me.arenaligi.lig.LigSistemi;
import me.arenaligi.model.DovuscuManager;
import me.arenaligi.veri.DovuscuVeri;
import me.mesleksistemi.api.MeslekAPI;

/**
 * Arena Ligi: 1v1 lig maçları, eşit kit, bahis. MeslekSistemi ile sadece MeslekAPI üzerinden konuşur.
 */
public class ArenaLigi extends JavaPlugin {

    private MeslekAPI meslek;
    private Mesaj mesaj;
    private LigSistemi lig;
    private DovuscuManager dovusculer;
    private DovuscuVeri veri;
    private Kuyruk kuyruk;
    private KayitNpc kayitNpc;

    // Dosyalar tek bir arka plan thread'inde sırayla yazılır
    private ExecutorService yazici;
    private volatile boolean kapaniyor = false;

    @Override
    public void onEnable() {
        meslek = Bukkit.getServicesManager().load(MeslekAPI.class);
        if (meslek == null) {
            getLogger().severe("MeslekSistemi'nin MeslekAPI servisi bulunamadı! MeslekSistemi'nin güncel sürümü kurulu olmalı. Eklenti kapatılıyor.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        yazici = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ArenaLigi-Kayit");
            t.setDaemon(true);
            return t;
        });

        mesaj = new Mesaj(this);
        lig = new LigSistemi(this);
        dovusculer = new DovuscuManager(this);
        veri = new DovuscuVeri(this);
        veri.yukle();
        kuyruk = new Kuyruk(this);
        kayitNpc = new KayitNpc(this);

        getServer().getPluginManager().registerEvents(kuyruk, this);
        getServer().getPluginManager().registerEvents(kayitNpc, this);

        ArenaKomut komut = new ArenaKomut(this);
        PluginCommand pc = getCommand("arena");
        if (pc != null) {
            pc.setExecutor(komut);
            pc.setTabCompleter(komut);
        }
        getLogger().info(dovusculer.hepsi().size() + " dövüşçü yüklendi.");
    }

    @Override
    public void onDisable() {
        kapaniyor = true;
        if (yazici != null) {
            yazici.shutdown();
            try {
                yazici.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (veri != null) veri.hemenKaydet();
    }

    /** İşi kayıt thread'inde çalıştırır; kapanışta ya da thread yoksa hemen çalıştırır. */
    public void arkaPlanda(Runnable is) {
        if (kapaniyor || yazici == null || yazici.isShutdown()) {
            is.run();
            return;
        }
        try {
            yazici.execute(is);
        } catch (RejectedExecutionException e) {
            is.run();
        }
    }

    public boolean kapaniyor() { return kapaniyor; }

    public MeslekAPI meslek() { return meslek; }
    public Mesaj mesaj() { return mesaj; }
    public LigSistemi lig() { return lig; }
    public DovuscuManager dovusculer() { return dovusculer; }
    public DovuscuVeri veri() { return veri; }
    public Kuyruk kuyruk() { return kuyruk; }
    public KayitNpc kayitNpc() { return kayitNpc; }
}
