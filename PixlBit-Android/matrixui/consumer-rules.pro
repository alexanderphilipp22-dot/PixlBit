# Consumer-ProGuard-Regeln des Moduls :matrixui
#
# Die Animation ist reines Kotlin/Compose und wird von R8 bereits korrekt
# behandelt. Es gibt keine Reflexion und keine nativen Bindings, daher sind
# hier keine Keep-Regeln fuer die Matrix-Klassen noetig.
# Wird spaeter doch einmal per Reflexion auf die Klassen zugegriffen
# (z. B. in Tests oder per Name), hier eintragen:

# -keep class com.mobile.utilities.calculator.matrix.** { *; }
