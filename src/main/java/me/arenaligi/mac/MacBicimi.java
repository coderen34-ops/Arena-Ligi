package me.arenaligi.mac;

/**
 * Maç biçimi: kaç takım, takımda kaç kişi. Şimdilik sadece 1v1 var; takım maçı ileride
 * yeni bir biçim olarak eklenecek (Mac ve MacYonetici takım listeleriyle çalışır).
 */
public interface MacBicimi {

    String ad();

    int takimSayisi();

    int takimBoyutu();

    default int oyuncuSayisi() { return takimSayisi() * takimBoyutu(); }

    /** 1v1: iki takım, birer kişi. */
    MacBicimi BIRE_BIR = new MacBicimi() {
        @Override public String ad() { return "1v1"; }
        @Override public int takimSayisi() { return 2; }
        @Override public int takimBoyutu() { return 1; }
    };
}
