# Captures des stores sur l'émulateur Essai_API34 (à sourcer : `. fastlane/demo/ecran.sh`).
# Toujours l'émulateur par son nom : `adb -e` compterait aussi un téléphone connecté en Wi-Fi.
A="$HOME/Android/Sdk/platform-tools/adb -s emulator-5554"
S="${CAPTURES:-/tmp/captures}"   # captures recadrées, nommées <langue>-<n>-<écran>.png
V="${PYTHON:-python3}"           # un Python avec Pillow
mkdir -p "$S"
# Le grep du système : celui du terminal de Claude Code est un autre outil.
G="command grep"

# Textes et descriptions visibles, avec le centre de chaque élément.
elements() {
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $A exec-out cat /sdcard/ui.xml | python3 -I -c '
import re, sys
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n).group(1)
    d = re.search(r"content-desc=\"([^\"]*)\"", n).group(1)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if (t or d) and b:
        x1, y1, x2, y2 = map(int, b.groups())
        print(f"{(x1+x2)//2} {(y1+y2)//2}\t{t}\t{d}")
'
}

# Premier élément dont le texte ou la description contient $1.
toucher() {
  local p; p=$(elements | $G -F -m1 -- "$1" | cut -f1)
  [ -n "$p" ] || { echo "introuvable : $1" >&2; return 1; }
  $A shell input tap $p
}

# Élément dont le texte est exactement $1 (« Ma maison.xlsx », pas son bouton d'aperçu).
toucher_exact() {
  local p; p=$(elements | awk -F'\t' -v t="$1" '$2 == t { print $1; exit }')
  [ -n "$p" ] || { echo "introuvable (exact) : $1" >&2; return 1; }
  $A shell input tap $p
}

attendre() { # attendre <texte> [secondes]
  local i=0
  until elements | $G -qF -- "$1"; do
    sleep 4; i=$((i + 4)); [ $i -ge "${2:-60}" ] && { echo "pas vu : $1" >&2; return 1; }
  done
}

# Capture sous la barre d'état, 1080 × 2150 (écran 1080 × 2400).
prendre() {
  sleep "${2:-3}"
  $A exec-out screencap -p > "$S/brut.png"
  $V -I -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').crop((0, 128, 1080, 2278)).save(sys.argv[2])" "$S/brut.png" "$S/$1.png"
}

# importer <locale> <maison> <bouton d'import> <titre de la confirmation> <bouton de confirmation>
# Vide l'application, la met dans la langue voulue et importe /sdcard/Download/<maison>.xlsx.
# Le sélecteur de fichiers est lent : parfois plus d'une minute.
importer() {
  [ "$($A shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo "pas l'émulateur" >&2; return 1; }
  $A shell am force-stop com.google.android.documentsui
  $A shell pm clear be.suivicompteurs.app >/dev/null
  $A shell cmd locale set-app-locales be.suivicompteurs.app --locales "$1"
  $A shell am start -n be.suivicompteurs.app/.MainActivity >/dev/null; sleep 15
  toucher "$3"; sleep 15; attendre "Show roots" 120 || return 1
  elements | $G -qF "	$2.xlsx	" || { $A shell input tap 73 191; sleep 5; toucher_exact "Downloads"; sleep 8; }
  toucher_exact "$2.xlsx"; attendre "$4" 90 && toucher_exact "$5"
  attendre "$2 –" 120   # puis la météo : dix ans de normales
}

# captures <l> <maison> <bouton des prévisions> <gaz> <comparaison> <justesse> : captures 1 à 7.
captures() {
  local l=$1
  attendre "$2 –" 120 || return 1; sleep 40
  prendre "$l-1-accueil" 1
  toucher "$3"; sleep 14; prendre "$l-2-previsions" 1
  toucher_exact "$4"; sleep 16; prendre "$l-3-detail" 1
  $A shell input swipe 540 1900 540 555 3000; prendre "$l-4-detail-graphiques" 3
  $A shell input swipe 540 1900 540 632 3000; $A shell input swipe 540 1900 540 1550 3000; prendre "$l-5-detail-signature" 3
  $A shell input keyevent KEYCODE_BACK; sleep 6
  $A shell input tap 1027 211; sleep 4; toucher "$5"; sleep 14; prendre "$l-6-comparaison" 1
  $A shell input keyevent KEYCODE_BACK; sleep 6
  $A shell input tap 1027 211; sleep 4; toucher "$6"; sleep 16; prendre "$l-7-justesse" 1
  $A shell input keyevent KEYCODE_BACK; sleep 4; $A shell input keyevent KEYCODE_BACK; sleep 4
}

# saisie <l> <compteur de gaz> <bouton « sans photo »> : capture 8, depuis l'accueil.
saisie() {
  $A shell am start -n be.suivicompteurs.app/.MainActivity >/dev/null 2>&1
  attendre "$2" 60 && toucher "$2"; sleep 8
  elements | $G -qF 'Only this time' && { toucher "Only this time"; sleep 8; }
  attendre "$3" 90 && toucher_exact "$3"; sleep 12
  prendre "$1-8-saisie" 1
}

# Exemple, en français :
#   importer fr-BE "Ma maison" "Importer un fichier Excel" "Importer le classeur" "Importer"
#   captures fr "Ma maison" "Consulter l'historique" "Gaz" "Comparaison des années" "Justesse des prévisions"
#   saisie fr "Ma maison – Gaz" "Sans photo"
