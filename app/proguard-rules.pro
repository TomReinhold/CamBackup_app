# Projektspezifische R8-Regeln

# ImageShrinker liest die Namen aller EXIF-Felder per Reflection aus den TAG_-Konstanten
# von ExifInterface. Ohne diese Regel würde R8 sie entfernen – EXIF-Daten gingen beim Verkleinern verloren.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}
