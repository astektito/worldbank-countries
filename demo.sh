#!/bin/sh
#
# Menu interactivo para correr y probar el servicio desde la terminal.
#
#   ./demo.sh
#
# Por que existe: la alternativa es pegar curls a mano durante la revision, y
# ahi es donde uno se equivoca de puerto o de codigo justo cuando lo estan
# mirando. Cada caso de aca imprime lo esperado al lado de lo obtenido, asi que
# no hay que comparar nada contra el enunciado mentalmente.
#
# KNOWN TRAP: no usa jq. jq no viene instalado en Windows y un script de demo
# que falla por una herramienta ausente es peor que no tener script. El JSON se
# formatea con python, que si esta.

set -u

cd "$(dirname "$0")" || exit 1

BASE="http://localhost:8080"
API="$BASE/api/countries"
ALT_PORT=8099
LOG="${TMPDIR:-/tmp}/wbc-demo.log"
ALT_LOG="${TMPDIR:-/tmp}/wbc-demo-502.log"

if [ -t 1 ]; then
    G='\033[32m'; R='\033[31m'; Y='\033[33m'; B='\033[36m'; D='\033[2m'; N='\033[0m'
else
    G=''; R=''; Y=''; B=''; D=''; N=''
fi

PASS=0
FAIL=0

# ---------------------------------------------------------------- utilidades

say()  { printf "%b\n" "$1"; }
rule() { printf "${D}%s${N}\n" "-----------------------------------------------------------------------"; }

title() {
    printf "\n${B}== %s ==${N}\n\n" "$1"
}

# Formatea JSON sin depender de jq.
pretty() {
    python -c 'import sys,json
raw=sys.stdin.read().strip()
if not raw:
    print("(sin cuerpo)"); sys.exit()
try:    print(json.dumps(json.loads(raw), indent=2, ensure_ascii=False))
except Exception: print(raw)' 2>/dev/null || cat
}

app_up() {
    curl -s -o /dev/null --max-time 2 "$API" 2>/dev/null
}

# expect <etiqueta> <status esperado> <metodo> <url>
expect() {
    label=$1; want=$2; method=$3; url=$4
    got=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -X "$method" "$url" 2>/dev/null)
    if [ "$got" = "$want" ]; then
        PASS=$((PASS + 1))
        printf "  ${G}OK${N}    %-34s espera %-3s  recibe ${G}%s${N}\n" "$label" "$want" "$got"
    else
        FAIL=$((FAIL + 1))
        printf "  ${R}FALLA${N} %-34s espera %-3s  recibe ${R}%s${N}\n" "$label" "$want" "$got"
    fi
}

# show <metodo> <url>  ->  imprime el status y el cuerpo formateado
show() {
    method=$1; url=$2
    printf "${D}  \$ curl -X %s \"%s\"${N}\n" "$method" "$url"
    body=$(curl -s -w '\n__STATUS__%{http_code}' --max-time 20 -X "$method" "$url" 2>/dev/null)
    status=$(printf '%s' "$body" | sed -n 's/.*__STATUS__//p')
    payload=$(printf '%s' "$body" | sed 's/__STATUS__.*//')
    case "$status" in
        2*) printf "  HTTP ${G}%s${N}\n" "$status" ;;
        4*) printf "  HTTP ${Y}%s${N}\n" "$status" ;;
        *)  printf "  HTTP ${R}%s${N}\n" "$status" ;;
    esac
    printf '%s' "$payload" | pretty | sed 's/^/  /'
}

# Mata lo que este escuchando en un puerto. spring-boot:run forkea la JVM, asi
# que matar Maven deja al hijo con el puerto tomado: hay que ir por PID.
free_port() {
    port=$1
    if command -v netstat >/dev/null 2>&1; then
        for pid in $(netstat -ano 2>/dev/null | grep -E ":$port[[:space:]]+.*LISTENING" | awk '{print $NF}' | sort -u); do
            taskkill //PID "$pid" //F >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null
        done
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -ti:"$port" 2>/dev/null | xargs -r kill -9 2>/dev/null
    fi
}

require_app() {
    if app_up; then return 0; fi
    say "${Y}La aplicacion no responde en $BASE.${N}"
    printf "Levantarla ahora? [S/n] "
    read -r answer
    case "${answer:-s}" in
        n|N) say "${D}Cancelado. Levantala con: ./mvnw spring-boot:run${N}"; return 1 ;;
    esac
    start_app || return 1
}

start_app() {
    free_port 8080
    say "${D}Arrancando (log en $LOG)...${N}"
    nohup ./mvnw -B spring-boot:run >"$LOG" 2>&1 &
    i=0
    while [ $i -lt 60 ]; do
        sleep 2
        if app_up; then
            say "${G}Arriba en $BASE${N}"
            return 0
        fi
        i=$((i + 1))
        printf "."
    done
    say "\n${R}No arranco en 120s. Ultimas lineas del log:${N}"
    tail -20 "$LOG"
    return 1
}

# ------------------------------------------------------------------ opciones

op_status() {
    title "Estado"
    printf "  java:  "; java -version 2>&1 | head -1
    if app_up; then
        say "  app:   ${G}arriba${N} en $BASE"
        n=$(curl -s --max-time 5 "$API" | python -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null || echo "?")
        say "  datos: $n paises importados"
        printf "  docs:  %s/swagger-ui.html\n" "$BASE"
        printf "  h2:    %s/h2-console  ${D}(la URL JDBC sale en el log de arranque)${N}\n" "$BASE"
    else
        say "  app:   ${R}no responde${N} en $BASE"
    fi
}

op_import_one() {
    require_app || return
    title "Importar un pais"
    printf "Codigo ISO (2 o 3 letras, Enter para CO): "
    read -r code
    show POST "$API/import?code=${code:-CO}"
    say "\n${D}Volver a correrlo con el mismo codigo devuelve 200 y el mismo id:"
    say "  es idempotente, actualiza la fila en vez de duplicarla.${N}"
}

op_cases() {
    require_app || return
    title "Los casos del enunciado"
    PASS=0; FAIL=0
    expect "import CO (crea)"       201 POST "$API/import?code=CO"
    expect "import CO (actualiza)"  200 POST "$API/import?code=CO"
    for c in PE MX ES JP; do
        expect "import $c"          201 POST "$API/import?code=$c"
    done
    expect "import ZZ (no existe)"  404 POST "$API/import?code=ZZ"
    expect "import 1 (invalido)"    400 POST "$API/import?code=1"
    expect "import sin ?code"       400 POST "$API/import"
    expect "GET lista"              200 GET  "$API"
    expect "GET /1"                 200 GET  "$API/1"
    expect "GET /9999"              404 GET  "$API/9999"
    rule
    if [ "$FAIL" -eq 0 ]; then
        printf "  ${G}%s de %s correctos${N}\n" "$PASS" "$((PASS + FAIL))"
    else
        printf "  ${R}%s fallas${N} de %s\n" "$FAIL" "$((PASS + FAIL))"
    fi
}

op_errors() {
    require_app || return
    title "Cuerpos de error"
    say "${D}Todos tienen la misma forma, {\"detail\":\"...\"}, sin excepcion.${N}\n"
    say "${B}400 - formato invalido${N}"
    show POST "$API/import?code=1"
    say "\n${B}400 - falta el parametro${N}"
    show POST "$API/import"
    say "\n${B}404 - codigo bien formado que no existe${N}"
    say "${D}  La API del World Bank contesta esto con HTTP 200: el 404 sale de"
    say "  mirar la forma del cuerpo, no el status. Ver la opcion 8.${N}"
    show POST "$API/import?code=ZZ"
    say "\n${B}404 - id inexistente${N}"
    show GET "$API/9999"
    say "\n${B}400 - id no numerico${N}"
    show GET "$API/abc"
    say "\n${D}Faltan dos que no se pueden provocar desde aca:"
    say "  502 -> opcion 5 (levanta una instancia contra un upstream muerto)"
    say "  409 -> conflicto de unicidad en importaciones concurrentes${N}"
}

op_list() {
    require_app || return
    title "Paises importados"
    curl -s --max-time 10 "$API" | python -c '
import sys, json
try:    data = json.load(sys.stdin)
except Exception: print("  respuesta no interpretable"); sys.exit()
if not data:
    print("  Todavia no hay ninguno. La lista devuelve [] y no un 404.")
    print("  Importa alguno con la opcion 2 o 3."); sys.exit()
print("  %-4s %-4s %-5s %-22s %-16s %-26s %-20s %s" % ("id","iso2","iso3","nombre","capital","region","ingresos","lat/lon"))
print("  " + "-"*136)
for c in data:
    lat, lon = c.get("latitude"), c.get("longitude")
    coord = "-" if lat is None or lon is None else "%s, %s" % (lat, lon)
    print("  %-4s %-4s %-5s %-22s %-16s %-26s %-20s %s" % (
        c.get("id"), c.get("iso2Code"), c.get("iso3Code"), (c.get("name") or "")[:22],
        (c.get("capitalCity") or "-")[:16], (c.get("region") or "-")[:26],
        (c.get("incomeLevel") or "-")[:20], coord))
print()
print("  %d en total." % len(data))
' 2>/dev/null || say "  ${R}No se pudo leer la lista.${N}"
}

op_502() {
    title "El 502, sin desconectar internet"
    say "Levanta una segunda instancia en el puerto $ALT_PORT con worldbank.base-url"
    say "apuntada al puerto 9 (discard), donde no escucha nada."
    say ""
    say "${D}Prueba dos cosas de una vez: que el 502 funciona, y que la URL es"
    say "configurable y no esta hardcodeada en ninguna clase de negocio.${N}"
    say ""
    free_port "$ALT_PORT"
    say "${D}Arrancando...${N}"
    nohup ./mvnw -B spring-boot:run \
        -Dspring-boot.run.arguments="--server.port=$ALT_PORT --worldbank.base-url=http://localhost:9/v2" \
        >"$ALT_LOG" 2>&1 &
    i=0
    while [ $i -lt 60 ]; do
        sleep 2
        curl -s -o /dev/null --max-time 2 "http://localhost:$ALT_PORT/api/countries" 2>/dev/null && break
        i=$((i + 1)); printf "."
    done
    printf "\n"
    if ! curl -s -o /dev/null --max-time 2 "http://localhost:$ALT_PORT/api/countries" 2>/dev/null; then
        say "${R}No arranco. Ultimas lineas:${N}"; tail -20 "$ALT_LOG"; return
    fi
    show POST "http://localhost:$ALT_PORT/api/countries/import?code=CO"
    say ""
    expect "import con upstream muerto" 502 POST "http://localhost:$ALT_PORT/api/countries/import?code=CO"
    expect "GET lista (sigue sana)"     200 GET  "http://localhost:$ALT_PORT/api/countries"
    say "\n${D}El endpoint local sigue respondiendo 200: la falla externa no se"
    say "propaga a lo que no depende de ella.${N}"
    free_port "$ALT_PORT"
    say "${D}Instancia de $ALT_PORT dada de baja.${N}"
}

op_tests() {
    title "Suite de tests"
    say "${D}\$ ./mvnw -B clean verify${N}\n"
    ./mvnw -B clean verify 2>&1 | grep -E "Tests run|BUILD|ERROR" | sed 's/^\[INFO\] //;s/^/  /'
    say ""
    say "${D}Ningun test toca la red real. Comprobacion:${N}"
    printf "  menciones a api.worldbank.org en la corrida: "
    hits=$(./mvnw -B clean test 2>&1 | grep -ci "api.worldbank.org")
    if [ "$hits" -eq 0 ]; then printf "${G}%s${N}\n" "$hits"; else printf "${R}%s${N}\n" "$hits"; fi
}

op_trap() {
    title "La trampa central: el error llega con HTTP 200"
    say "Contra la API real del World Bank, un codigo inexistente:${N}\n"
    printf "${D}  \$ curl -i \"https://api.worldbank.org/v2/country/ZZ?format=json\"${N}\n"
    curl -s -i --max-time 15 "https://api.worldbank.org/v2/country/ZZ?format=json" 2>/dev/null \
        | grep -iE "^HTTP|message" | sed 's/^/  /' | head -5
    say ""
    say "${Y}Devuelve 200, no 404.${N} Un cliente que solo mire el status trata el"
    say "error como exito y explota despues, al mapear."
    say ""
    say "Y la respuesta correcta es una tupla heterogenea, [{metadatos},[paises]],"
    say "donde el indice 1 no existe cuando hay error:\n"
    printf "${D}  \$ curl \"https://api.worldbank.org/v2/country/CO?format=json\"${N}\n"
    curl -s --max-time 15 "https://api.worldbank.org/v2/country/CO?format=json" 2>/dev/null | pretty | sed 's/^/  /' | head -22
    say ""
    say "${D}Por eso el parseo discrimina por la forma del cuerpo y vive aislado en"
    say "WorldBankResponseParser, del que JsonNode no sale.${N}"
}

op_stop() {
    title "Bajar la aplicacion"
    free_port 8080
    free_port "$ALT_PORT"
    say "${G}Puertos 8080 y $ALT_PORT liberados.${N}"
    say "${D}Se matan por PID a proposito: spring-boot:run forkea la JVM, y matar"
    say "Maven deja al hijo con el puerto tomado.${N}"
}

# --------------------------------------------------------------------- menu

menu() {
    printf "\n"
    rule
    printf " ${B}Countries Service${N}  ${D}- World Bank import microservice${N}\n"
    if app_up; then
        printf " ${G}*${N} app arriba en %s\n" "$BASE"
    else
        printf " ${R}*${N} app no responde en %s\n" "$BASE"
    fi
    rule
    cat <<'MENU'
  1) Estado del entorno y de la app
  2) Importar un pais (elegis el codigo)
  3) Correr los casos del enunciado          201 200 404 400 ...
  4) Ver los cuerpos de error                400 404
  5) Probar el 502 con el upstream muerto    tambien prueba que la URL es configurable
  6) Listar los paises importados
  7) Correr la suite de tests                ./mvnw clean verify
  8) Ver la trampa del HTTP 200 en vivo      contra la API real
  9) Levantar la app
  0) Bajar la app y salir
  q) Salir dejando la app corriendo
MENU
    rule
    printf "Opcion: "
}

while :; do
    menu
    read -r choice || break
    case "$choice" in
        1) op_status ;;
        2) op_import_one ;;
        3) op_cases ;;
        4) op_errors ;;
        5) op_502 ;;
        6) op_list ;;
        7) op_tests ;;
        8) op_trap ;;
        9) start_app ;;
        0) op_stop; say "${D}Listo.${N}"; exit 0 ;;
        q|Q) say "${D}La app sigue corriendo. Para bajarla: ./demo.sh y opcion 0.${N}"; exit 0 ;;
        "") ;;
        *) say "${Y}Opcion no reconocida: $choice${N}" ;;
    esac
    printf "\n${D}Enter para volver al menu...${N}"
    read -r _
done
