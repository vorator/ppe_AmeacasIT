import csv
from collections import defaultdict

# Ajusta o caminho se o teu terminal correr fora da pasta collector
ficheiro_csv = "collector/chamadas_sistema.csv" 
processo_alvo = "notepad.exe" # Substitui pelo programa que usaste nesse minuto

sequencia_estados = []

print(f"[INFO] A devorar o CSV gigante. A filtrar apenas operações do '{processo_alvo}'...")

# Lemos em stream para não saturar os teus 16GB de RAM
with open(ficheiro_csv, mode='r', encoding='utf-8-sig', errors='ignore') as f:
    reader = csv.DictReader(f)
    
    for linha in reader:
        processo = linha.get("Process Name", "")
        operacao = linha.get("Operation", "")
        
        # Filtramos apenas o processo que queremos modelar
        if processo.lower() == processo_alvo.lower():
            
            # Mapeamento do ProcMon para o nosso "Espaço de Estados" (S)
            if "CreateFile" in operacao:
                sequencia_estados.append("OPEN")
            elif "ReadFile" in operacao:
                sequencia_estados.append("READ")
            elif "WriteFile" in operacao:
                sequencia_estados.append("WRITE")
            elif "CloseFile" in operacao:
                sequencia_estados.append("CLOSE")
            elif "TCP" in operacao or "UDP" in operacao:
                sequencia_estados.append("NETWORK")

print("\n[SUCESSO] Filtragem estocástica concluída!")
print(f"Total de chamadas úteis capturadas: {len(sequencia_estados)}")

if len(sequencia_estados) > 0:
    print(f"\nAmostra do Rasto de Execução (Primeiros 20 estados):")
    print(" -> ".join(sequencia_estados[:20]))
else:
    print("\n[AVISO] Não foram detetadas operações para este processo. Tens a certeza que o abriste durante esse minuto?")

print("\n[INFO] A calcular a Matriz de Transição de Markov (Fase de Treino)...")


# Treinar a Matriz de Transição (P).

# Dicionários para contar as transições absolutas
contagem_transicoes = defaultdict(lambda: defaultdict(int))
contagem_estados = defaultdict(int)

# 1. Contar as ocorrências (Frequência Absoluta)
for i in range(len(sequencia_estados) - 1):
    estado_atual = sequencia_estados[i]
    proximo_estado = sequencia_estados[i+1]
    
    contagem_transicoes[estado_atual][proximo_estado] += 1
    contagem_estados[estado_atual] += 1

# 2. Calcular as Probabilidades Condicionais (Frequência Relativa)
matriz_markov = defaultdict(dict)

print("\n--- MATRIZ DE TRANSIÇÃO (Comportamento Legítimo) ---")
for estado_origem, transicoes in contagem_transicoes.items():
    total_saidas = contagem_estados[estado_origem]
    
    for estado_destino, quantidade in transicoes.items():
        # p_ij = Contagem(i -> j) / Total Saídas(i)
        probabilidade = quantidade / total_saidas
        matriz_markov[estado_origem][estado_destino] = probabilidade
        
        # Formata a saída para percentagem com 2 casas decimais
        print(f"P({estado_origem} -> {estado_destino}) = {probabilidade:.4f} ({probabilidade*100:.2f}%)")

print("----------------------------------------------------")