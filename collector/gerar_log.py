from collections import deque
import csv
import time
import psutil

# ==============================================================================
# FASE 0: REGRAS & ESTRUTURAS
# ==============================================================================

# As 6 famílias de ações do sistema (Espaço de escuta S)
STATES = ['INIT', 'FILE_IO', 'PROC', 'NET_OUT', 'PRIV_EXEC', 'MEM_ALLOC']
STATE_IDX = {s: i for i, s in enumerate(STATES)}

DURACAO_SEGUNDOS = 5.0     # duração da monitarização de treino 
INTERVALO_LEITURA = 0.01    # intervalo de snapshot (50 Hz)
FATOR_AUMENTO = 2.0         # 100% de aumento
MIN_EVENTOS_NORMA = 5       # Piso mínimo para evitar falsos positivos

def ler_contadores_kernel():
    """Consulta API do SO p/ obter contadores de desempenho naquele instante."""
    cpu = psutil.cpu_times()            # CPU: tempo de execução em modo user e system
    disco = psutil.disk_io_counters()   # Disco: contadores de E/S
    rede = psutil.net_io_counters()     # Rede: contadores de pacotes
    stats = psutil.cpu_stats()          # Estatísticas do CPU
    n_pids = len(psutil.pids())         # CPU: número de processos ativos
    mem = psutil.virtual_memory()       # Memória: uso atual da memória virtual

    return {
        'tempo': time.perf_counter(),
        'pids': n_pids,
        'io_ops': (disco.read_count + disco.write_count) if disco else 0,
        'net_pkts': (rede.packets_sent + rede.packets_recv) if rede else 0,
        'cpu_user': cpu.user,
        'cpu_sys': cpu.system,
        #'syscalls': stats.syscalls,
        'mem_used': mem.used,
        'ctx_switches': stats.ctx_switches
    }

def detetar_eventos(anterior, atual):
    """Calcula os deltas de 20ms e devolve os grupos ativados e as métricas."""
    delta_pids = abs(atual['pids'] - anterior['pids'])
    delta_io = atual['io_ops'] - anterior['io_ops']
    delta_net = atual['net_pkts'] - anterior['net_pkts']
    delta_user = atual['cpu_user'] - anterior['cpu_user']
    delta_sys = atual['cpu_sys'] - anterior['cpu_sys']
    delta_ctx = atual['ctx_switches'] - anterior['ctx_switches']
    delta_mem = atual['mem_used'] - anterior['mem_used']

    eventos = []
    if delta_pids > 0 or delta_ctx > 500:
        eventos.append('INIT')
    if delta_io > 0:
        eventos.append('FILE_IO')
    if delta_user > 0:
        eventos.append('PROC')
    if delta_sys > delta_user and delta_sys > 0:
        eventos.append('PRIV_EXEC')
    if delta_net > 0:
        eventos.append('NET_OUT')
    if delta_mem > 1048576:  # > 1 MB
        eventos.append('MEM_ALLOC')

    deltas = (delta_io, delta_net, delta_ctx, delta_mem)
    return eventos, deltas

# ==============================================================================
# FASE 1: CALIBRAÇÃO DA NORMA (60 SEGUNDOS)
# ==============================================================================

print(f"=== [FASE 1] A CONSTRUIR A NORMA ({DURACAO_SEGUNDOS:.0f}s) ===")
print("Usa o teu computador normalmente (Word, Browser, guardar ficheiro)...")

anterior = ler_contadores_kernel()
t_inicio = anterior['tempo']
t_ultimo_evento = t_inicio

registos = []
id_ciclo = 0

# JANELA DESLIZANTE: por grupo (S) guardamos os timestamps dos últimos eventos para calcular a frequência
janelas = {s: deque() for s in STATES}

while (time.perf_counter() - t_inicio) < DURACAO_SEGUNDOS:
    time.sleep(INTERVALO_LEITURA)
    atual = ler_contadores_kernel()
    agora = atual['tempo']
    t_relativo = agora - t_inicio

    eventos_ciclo, (delta_io, delta_net, delta_ctx, delta_mem) = detetar_eventos(anterior, atual)

    if eventos_ciclo:
        id_ciclo += 1
        intervalo_chegada = agora - t_ultimo_evento
        t_ultimo_evento = agora

        for estado in eventos_ciclo:
            janelas[estado].append(agora)
            registos.append([
                id_ciclo,
                round(t_relativo, 6),
                round(intervalo_chegada, 6),
                delta_io,
                delta_net,
                delta_ctx,
                delta_mem,
                estado,
                STATE_IDX[estado]
            ])

    anterior = atual

# Gravar a norma em CSV
nome_ficheiro = 'collector/treino/norma_poisson.csv'
with open(nome_ficheiro, mode='w', newline='', encoding='utf-8') as f:
    writer = csv.writer(f)
    writer.writerow([
        'cycle_id', 'timestamp_s', 'inter_arrival_s',
        'delta_disk_ops', 'delta_net_pkts', 'delta_ctx_switches',
        'delta_mem_bytes', 'syscall_family', 'state_id'
    ])
    writer.writerows(registos)

# Calcular o Lambda normal (eventos por segundo) para cada grupo
lambda_norma = {}
limiar_alerta = {}

print(f"\nNorma gravada em '{nome_ficheiro}' ({len(registos)} eventos em {id_ciclo} ciclos).")
print(f"\n--- BASELINE CALCULADA (Fator de Alerta: {FATOR_AUMENTO}x) ---")
for s in STATES:
    ocorrencias = len(janelas[s])
    lam = ocorrencias / DURACAO_SEGUNDOS
    lambda_norma[s] = lam

    # Aplica um piso mínimo para grupos que tiveram 0 ocorrências na calibração
    lam_base = max(lam, MIN_EVENTOS_NORMA / DURACAO_SEGUNDOS)
    limiar_alerta[s] = lam_base * FATOR_AUMENTO

    tempo_medio_chegada = (1.0 / lam) if lam > 0 else float('inf')
    print(f"  {s:10s} | \u03BB_norma: {lam:6.2f} ev/s | 1/\u03BB: {tempo_medio_chegada:6.3f}s | Alerta >= {limiar_alerta[s]:6.2f} ev/s")


# ==============================================================================
# FASE 2: MONITORIZAÇÃO CONTÍNUA (JANELA DESLIZANTE DE 60 SEGUNDOS)
# ==============================================================================

print(f"\n=== [FASE 2] MONITORIZAÇÃO ATIVA (Ctrl+C para terminar) ===")

try:
    while True:
        time.sleep(INTERVALO_LEITURA)
        atual = ler_contadores_kernel()
        agora = atual['tempo']

        eventos_ciclo, _ = detetar_eventos(anterior, atual)
        for estado in eventos_ciclo:
            janelas[estado].append(agora)

        # Limpar eventos com mais de 60 segundos e verificar anomalias
        limite_tempo = agora - DURACAO_SEGUNDOS
        grupos_em_alerta = []

        for s in STATES:
            # Remove pela esquerda tudo o que saiu da janela de 60s
            while janelas[s] and janelas[s][0] < limite_tempo:
                janelas[s].popleft()

            lambda_atual = len(janelas[s]) / DURACAO_SEGUNDOS

            if lambda_atual >= limiar_alerta[s]:
                grupos_em_alerta.append((s, lambda_atual, lambda_norma[s]))

        # Se algum grupo disparou, para o programa e reporta
        if grupos_em_alerta:
            print("\n" + "!" * 60)
            print("[ALERTA DE ANOMALIA VOLUMÉTRICA] Aumento crítico detetado!")
            for s, lam_atual, lam_norm in grupos_em_alerta:
                racio = (lam_atual / lam_norm) if lam_norm > 0 else float('inf')
                print(f"  -> Grupo: {s:10s} | \u03BB_atual: {lam_atual:.2f} ev/s | \u03BB_norma: {lam_norm:.2f} ev/s ({racio:.1f}x)")
            print("!" * 60)
            break

        anterior = atual

except KeyboardInterrupt:
    print("\n[INFO] Monitorização terminada manualmente pelo utilizador.")