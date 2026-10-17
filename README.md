# Ando Talkie

Android Walkie Talkie alkalmazás: két eszköz közvetlenül (peer-to-peer) kapcsolódik **Bluetooth-on** vagy **azonos Wi‑Fi hálózaton**, a teljes forgalom végpontok között titkosított.

## Funkciók
- **Walkie Talkie** üzemmód: nyomva tartva beszélsz (fél-duplex élő hang)
- **Üzenetek, fájlok, képek, videók, hangüzenetek** küldése
- **Hívás** két eszköz között (full-duplex, titkosított hang)
- **Beszélgetések tárolása** az eszközön (a szövegek Android Keystore kulccsal titkosítva)
- **Segítő varázsló** (Kapcsolat fül → „Nem megy? Segítő varázsló”), ha az automatikus keresés nem találja a másik eszközt
- **Alkalmazászár**: PIN kód és/vagy ujjlenyomat, állítható késleltetés, képernyőkép-tiltás
- **APK megosztása offline**: Beállítások → „APK megosztása” (vagy Kapcsolat fül) – a telepített release APK-t Bluetooth-on, Quick Share-en vagy bármely más módon továbbadhatod, vagy fájlba mentheted
- **Material 3** felület, világos / sötét / rendszer téma, dinamikus színek (Android 12+)

## Titkosítás
1. Kapcsolatonként ideiglenes P‑256 ECDH kulcscsere (forward secrecy).
2. Az átiratot mindkét fél aláírja a hosszú távú, Android Keystore-ban tárolt P‑256 azonosító kulcsával.
3. Mindkét kijelzőn megjelenik ugyanaz a 6 számjegyű biztonsági kód (SAS) – első kapcsolódáskor a felhasználók összevetik, így a közbeékelődéses támadás kiszűrhető. Az ellenőrzött eszköz ezután megbízható.
4. Minden keret AES‑256‑GCM, irányonként külön kulccsal, számlálós nonce-szal.

## Használat
1. Mindkét eszközre telepítsd az APK-t, és engedélyezd a kért engedélyeket.
2. **Kapcsolat** fül → Wi‑Fi vagy Bluetooth → válaszd ki a másik eszközt (vagy használd a varázslót).
3. Hasonlítsd össze a biztonsági kódot, nyomj az „Egyezik” gombra mindkét eszközön.

## Build
```
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest      # kézfogás / titkosítás egységteszt
./gradlew publishRelease          # aláírt release APK -> releases/AndoTalkie-v<verzió>-release.apk
```
Minden verziószámhoz (`app/build.gradle.kts`: `appVersionName`) a `releases/` mappában található a tesztelhető, release-elt APK.

> A `keystore/test-release.keystore` **csak tesztelési célú**, nyilvános kulcstár. Éles terjesztéshez saját kulcstárat használj.

## Ismert korlátok
- Egyszerre egy partnerrel kapcsolódik.
- Bluetooth Classic (RFCOMM) – a másik eszközön nyitva kell lennie az alkalmazásnak.
