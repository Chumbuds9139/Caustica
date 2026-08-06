#!/usr/bin/env bash
#
# Apaga os artifacts "caustica-bundled-jar" antigos deste repositorio.
#
# POR QUE ISTO EXISTE
#   A cota de artifacts do GitHub Actions em conta Free e' 500 MB. Cada build guarda um jar de
#   ~38 MB, e com a retencao padrao de 90 dias eles se acumulam ate a cota encher. Quando isso
#   acontece TODO upload passa a falhar, os jobs nativos ficam vermelhos e o job que compila o
#   mod nem chega a rodar -- ou seja, o CI para de validar o codigo.
#
#   Os jars sao apenas builds de commits que ja estao no historico: qualquer um pode ser
#   refeito com `./gradlew build`. Os shims (ngxshim-*) ocupam ~11 MB no total e sao ignorados
#   por este script -- nao vale a pena mexer neles.
#
# COMO USAR
#   1) Autentique-se com a SUA conta (o agente nao tem permissao para apagar artifacts):
#        gh auth login
#   2) Veja o que seria apagado, sem apagar nada:
#        ./limpar-artifacts.sh
#   3) Apague de verdade:
#        ./limpar-artifacts.sh --apagar
#
#   Para preservar os N jars mais recentes (padrao: 0, apaga todos):
#        ./limpar-artifacts.sh --apagar --manter 1
#
# DEPOIS
#   O GitHub recalcula o uso a cada 6-12 horas, entao os uploads podem demorar um pouco para
#   voltar a funcionar mesmo apos a exclusao.

set -euo pipefail

REPO="xysgottaken2/testingcasutica"
NOME="caustica-bundled-jar"

apagar=false
manter=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apagar) apagar=true; shift ;;
    --manter) manter="${2:-0}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "opcao desconhecida: $1" >&2; exit 2 ;;
  esac
done

command -v gh >/dev/null || { echo "ERRO: 'gh' nao encontrado. Instale o GitHub CLI." >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "ERRO: nao autenticado. Rode 'gh auth login' primeiro." >&2; exit 1; }

echo "Consultando artifacts de ${REPO}..."
# id<TAB>tamanho<TAB>data, do mais novo para o mais antigo.
lista="$(gh api "repos/${REPO}/actions/artifacts?per_page=100" --paginate \
  --jq ".artifacts[] | select(.expired==false) | select(.name==\"${NOME}\") | \"\(.id)\t\(.size_in_bytes)\t\(.created_at)\"" \
  | sort -k3 -r)"

if [ -z "$lista" ]; then
  echo "Nenhum artifact '${NOME}' encontrado. Nada a fazer."
  exit 0
fi

total_n="$(printf '%s\n' "$lista" | wc -l | tr -d ' ')"
total_mb="$(printf '%s\n' "$lista" | awk -F'\t' '{s+=$2} END {printf "%.0f", s/1024/1024}')"
echo "Encontrados: ${total_n} jars, ${total_mb} MB no total."

if [ "$manter" -gt 0 ]; then
  echo "Preservando os ${manter} mais recentes."
  alvos="$(printf '%s\n' "$lista" | tail -n +$((manter + 1)))"
else
  alvos="$lista"
fi

if [ -z "$alvos" ]; then
  echo "Nada sobrou para apagar depois de preservar ${manter}."
  exit 0
fi

alvo_n="$(printf '%s\n' "$alvos" | wc -l | tr -d ' ')"
alvo_mb="$(printf '%s\n' "$alvos" | awk -F'\t' '{s+=$2} END {printf "%.0f", s/1024/1024}')"

if [ "$apagar" != true ]; then
  echo
  echo "--- SIMULACAO (nada foi apagado) ---"
  echo "Seriam apagados ${alvo_n} jars, liberando ${alvo_mb} MB."
  echo "Sobraria: $((total_mb - alvo_mb)) MB de jars + ~11 MB de shims."
  echo
  echo "Para apagar de verdade:  $0 --apagar"
  exit 0
fi

echo
echo "Apagando ${alvo_n} jars (${alvo_mb} MB)..."
ok=0; falhou=0
while IFS=$'\t' read -r id tamanho data; do
  [ -n "$id" ] || continue
  if gh api -X DELETE "repos/${REPO}/actions/artifacts/${id}" --silent 2>/dev/null; then
    ok=$((ok + 1))
    printf '\r  apagados: %d/%d' "$ok" "$alvo_n"
  else
    falhou=$((falhou + 1))
    printf '\n  FALHOU id=%s (permissao? veja "gh auth status")\n' "$id"
  fi
done <<< "$alvos"
printf '\n'

echo "Concluido: ${ok} apagados, ${falhou} falharam."
restante="$(gh api "repos/${REPO}/actions/artifacts?per_page=100" --paginate \
  --jq '.artifacts[] | select(.expired==false) | .size_in_bytes' 2>/dev/null \
  | awk '{s+=$1; n++} END {printf "%d artifacts, %.0f MB", n, s/1024/1024}')"
echo "Uso atual: ${restante}  (limite Free: 500 MB)"
echo
echo "Observacao: o GitHub recalcula a cota a cada 6-12 horas; se um upload ainda falhar"
echo "logo apos a limpeza, tente novamente mais tarde."
