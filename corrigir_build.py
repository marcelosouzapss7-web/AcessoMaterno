#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
corrigir_build.py — correção pontual e segura (apenas .replace(), sem regex).
Corrige DOIS problemas independentes:

  PROBLEMA 1 (Kotlin / build do Android Studio — 3 erros na linha ~642):
      "return@when" é inválido em Kotlin (when não é rótulo válido).
      handleRemoteGesture é uma função normal (Unit), então 'return'
      simples basta. Substituição exata e única.

  PROBLEMA 2 (JavaScript dentro da string _HTML_EMULADOR_FLUTUANTE do
  server.py): "var aps = data.aparelhos ||;" — operador || sem operando
  à direita = SyntaxError no navegador. Fallback seguro: array vazio [].

USO:  python3 corrigir_build.py  (rode na pasta onde estão server.py
      e MyAccessibilityService.kt; ou edite os caminhos abaixo).
NÃO altera nenhuma outra rota, string ou variável global.
"""
import sys, os

# ---- Ajuste os caminhos se necessário ----
CAMINHO_SERVER = "server.py"
CAMINHO_KOTLIN = "MyAccessibilityService.kt"

def corrigir(caminho, substituicoes, nome):
    if not os.path.exists(caminho):
        print(f"[AVISO] {nome} não encontrado em: {caminho} — pulado.")
        return False
    with open(caminho, "r", encoding="utf-8") as f:
        original = f.read()
    conteudo = original
    total = 0
    for antigo, novo in substituicoes:
        n = conteudo.count(antigo)
        if n == 0:
            print(f"[{nome}] padrão não encontrado (provavelmente já corrigido): {antigo[:60]!r}...")
            continue
        conteudo = conteudo.replace(antigo, novo)
        total += n
        print(f"[{nome}] substituído {n}x: {antigo[:60]!r} -> {novo[:60]!r}")
    if conteudo != original:
        backup = caminho + ".bak"
        with open(backup, "w", encoding="utf-8") as f:
            f.write(original)
        with open(caminho, "w", encoding="utf-8") as f:
            f.write(conteudo)
        print(f"[{nome}] OK — arquivo atualizado. Backup em: {backup}")
    else:
        print(f"[{nome}] nenhuma alteração necessária.")
    return True

# ---- 1) server.py: JS dentro do template do emulador flutuante ----
corrigir(
    CAMINHO_SERVER,
    [
        # Fallback seguro: ||;  ->  || []
        ("var aps = data.aparelhos ||;", "var aps = data.aparelhos || [];"),
        # Variante com espaço (||  ;) por precaução
        ("var aps = data.aparelhos || ;", "var aps = data.aparelhos || [];"),
    ],
    "server.py",
)

# ---- 2) MyAccessibilityService.kt: return@when -> return ----
# Usa contexto multi-linha para garantir substituição EXATA e única.
corrigir(
    CAMINHO_KOTLIN,
    [
        (
            'enviarLog("END órfão ignorado (sem START anterior) — anti-ghost.")\n'
            '                    restaurarBloqueioLocal()\n'
            '                    return@when',
            'enviarLog("END órfão ignorado (sem START anterior) — anti-ghost.")\n'
            '                    restaurarBloqueioLocal()\n'
            '                    return',
        ),
        # Fallback: se a indentação for diferente, pega o token isolado.
        ("return@when", "return"),
    ],
    "MyAccessibilityService.kt",
)

print("\nConcluído. Recompile o projeto no Android Studio.")
