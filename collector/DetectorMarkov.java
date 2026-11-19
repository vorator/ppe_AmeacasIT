package collector;
import sun.misc.Signal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DetectorMarkov {

    // ==============================================================================
    // FASE 0: REGRAS & ESTRUTURAS
    // ==============================================================================

    // ficheiros do ProcMon e da matriz de transição
    private static final String PROCMON_EXE = "collector/Procmon64.exe"; 
    private static final String FICHEIRO_PML = "collector/recolha_temp.pml";
    private static final String FICHEIRO_CSV = "collector/chamadas_temp.csv";
    private static final String FICHEIRO_MATRIZ = "collector/matriz_markov.csv";

    // parametros de treino e deteção (em milissegundos)
    private static final long TEMPO_TREINO_MS = 60 * 1000;
    private static final long TEMPO_DETECAO_MS = 15 * 1000; 
    
    // Alerta se P(A->B) < 1%
    private static final double LIMIAR_ANOMALIA = 0.01;             
    
    // Janela deslizante
    private static final int TAMANHO_JANELA = 20;
    private static final double LIMIAR_MISS_RATE = 0.50;

    private static final AtomicBoolean cancelarOperacao = new AtomicBoolean(false);

    // objetos alvo para monitorização
    private static final Set<String> PROCESSOS_ALVO = Set.of(
        "notepad.exe",
        "chrome.exe", 
        "msedge.exe",
        "winword.exe", 
        "excel.exe", 
        "powerpnt.exe",
        "cmd.exe", 
        "powershell.exe", 
        "windowsterminal.exe"
    );

    public static void main(String[] args) {
        
        Signal.handle(new Signal("INT"), sig -> {
            cancelarOperacao.set(true);
            System.out.println("\n[INFO] Interrupção detetada. A regressar ao menu...");
        });
        
        Scanner scanner = new Scanner(System.in);
                
        while (true) {
            cancelarOperacao.set(false);
            System.out.println("\n=== DETETOR ESTOCÁSTICO DE AMEAÇAS (MARKOV) ===");
            System.out.println("1 - FASE 1: Treinar Matriz de Transição (60s)");
            System.out.println("2 - FASE 2: Escuta Silenciosa Contínua (Ctrl+C para voltar ao menu)");
            System.out.println("x - Sair");
            System.out.print("Opção: ");

            if (!scanner.hasNextLine()) break;
            String opcao = scanner.nextLine().trim().toLowerCase();

            if ("x".equals(opcao)) {
                System.out.println("A terminar...");
                break;
            }

            try {
                if ("1".equals(opcao)) {
                    System.out.println("[FASE 1] A treinar durante 60s... (Ctrl+C para cancelar)");
                    if (capturarProcMon(TEMPO_TREINO_MS)) {
                        treinarEGuardarMatriz();
                        System.out.println("[OK] Matriz guardada em: " + FICHEIRO_MATRIZ);
                    }
                } else if ("2".equals(opcao)) {
                    Map<String, Map<String, Map<String, Double>>> matriz = carregarMatriz();
                    if (matriz.isEmpty()) {
                        System.err.println("[ERRO] Matriz inexistente. Corre a Fase 1 primeiro.");
                        continue;
                    }
                    System.out.println("[FASE 2] Escuta silenciosa ativa. Apenas alertas serão mostrados (Ctrl+C para parar)...");
                    Map<String, String> ultimoEstado = new HashMap<>();
                    Map<String, Deque<Boolean>> janelas = new HashMap<>();

                    while (!cancelarOperacao.get()) {
                        if (capturarProcMon(TEMPO_DETECAO_MS)) {
                            avaliarTransicoes(matriz, ultimoEstado, janelas);
                        }
                    }
                } else {
                    System.out.println("Opção inválida.");
                }
            } catch (Exception e) {
                System.err.println("[ERRO] Falha na execução. O terminal está a correr como Administrador?");
            }
        }
        scanner.close();
    }
    
    // -------------------------------------------------------------------------
    // ORQUESTRAÇÃO DO PROCMON
    // -------------------------------------------------------------------------
    private static boolean capturarProcMon(long duracaoMs) throws Exception {
        Files.deleteIfExists(Paths.get(FICHEIRO_PML));
        Files.deleteIfExists(Paths.get(FICHEIRO_CSV));

        new ProcessBuilder(
            PROCMON_EXE, "/AcceptEula", "/Quiet", "/Minimized", "/BackingFile", FICHEIRO_PML
        ).start();

        // Espera em blocos de 500ms para reagir quase instantaneamente ao Ctrl+C
        long tempoDecorrido = 0;
        while (tempoDecorrido < duracaoMs && !cancelarOperacao.get()) {
            if (System.in.available() > 0) {
                while (System.in.available() > 0) System.in.read(); // Limpa o buffer do Enter
                cancelarOperacao.set(true);
                System.out.println("[INFO] Interrompido pelo utilizador. A regressar ao menu...");
                break;
            }
            Thread.sleep(500);
            tempoDecorrido += 500;
        }

        // Termina sempre o ProcMon de forma limpa e silenciosa
        Process pStop = new ProcessBuilder(PROCMON_EXE, "/AcceptEula", "/Quiet", "/Terminate").start();
        pStop.waitFor();

        // Se o utilizador carregou em Ctrl+C, descarta a conversão e volta logo ao menu
        if (cancelarOperacao.get()) {
            return false;
        }

        // /Quiet e /Minimized também na conversão para não abrir a janela do ProcMon
        Process pConvert = new ProcessBuilder(
            PROCMON_EXE, "/AcceptEula", "/Quiet", "/Minimized", "/OpenLog", FICHEIRO_PML, "/SaveAs", FICHEIRO_CSV
        ).start();
        pConvert.waitFor();

        return true;
    }

    // -------------------------------------------------------------------------
    // FASE 1: CONSTRUIR E GUARDAR A MATRIZ DE TRANSIÇÃO
    // -------------------------------------------------------------------------
    private static void treinarEGuardarMatriz() throws IOException {
        Map<String, Map<String, Map<String, Integer>>> contagens = new HashMap<>();
        Map<String, Map<String, Integer>> totaisOrigem = new HashMap<>();
        Map<String, String> ultimoEstado = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(FICHEIRO_CSV))) {
            br.readLine();
            String linha;
            while ((linha = br.readLine()) != null) {
                String[] campos = parseLinhaCSV(linha);
                if (campos == null) continue;

                String processo = campos[1].toLowerCase();
                if (!PROCESSOS_ALVO.contains(processo)) continue;

                String estadoAtual = mapearEstado(campos[3]);
                if (estadoAtual == null) continue;

                String estadoAnterior = ultimoEstado.get(processo);
                if (estadoAnterior != null) {
                    contagens
                        .computeIfAbsent(processo, k -> new HashMap<>())
                        .computeIfAbsent(estadoAnterior, k -> new HashMap<>())
                        .merge(estadoAtual, 1, Integer::sum);

                    totaisOrigem
                        .computeIfAbsent(processo, k -> new HashMap<>())
                        .merge(estadoAnterior, 1, Integer::sum);
                }
                ultimoEstado.put(processo, estadoAtual);
            }
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(FICHEIRO_MATRIZ))) {
            pw.println("Processo,Origem,Destino,Probabilidade");
            for (String proc : contagens.keySet()) {
                for (Map.Entry<String, Map<String, Integer>> entryOrigem : contagens.get(proc).entrySet()) {
                    String origem = entryOrigem.getKey();
                    int total = totaisOrigem.get(proc).get(origem);

                    for (Map.Entry<String, Integer> entryDestino : entryOrigem.getValue().entrySet()) {
                        String destino = entryDestino.getKey();
                        double prob = (double) entryDestino.getValue() / total;
                        pw.printf(Locale.US, "%s,%s,%s,%.6f%n", proc, origem, destino, prob);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // FASE 2: CARREGAR MATRIZ E DETETAR ANOMALIAS
    // -------------------------------------------------------------------------
    private static Map<String, Map<String, Map<String, Double>>> carregarMatriz() throws IOException {
        Map<String, Map<String, Map<String, Double>>> matriz = new HashMap<>();
        if (!Files.exists(Paths.get(FICHEIRO_MATRIZ))) return matriz;

        try (BufferedReader br = new BufferedReader(new FileReader(FICHEIRO_MATRIZ))) {
            br.readLine();
            String linha;
            while ((linha = br.readLine()) != null) {
                String[] p = linha.split(",");
                if (p.length < 4) continue;
                matriz
                    .computeIfAbsent(p[0], k -> new HashMap<>())
                    .computeIfAbsent(p[1], k -> new HashMap<>())
                    .put(p[2], Double.parseDouble(p[3]));
            }
        }
        return matriz;
    }

    private static void avaliarTransicoes(
            Map<String, Map<String, Map<String, Double>>> matriz,
            Map<String, String> ultimoEstado, 
            Map<String, Deque<Boolean>> janelas) throws IOException {

        try (BufferedReader br = new BufferedReader(new FileReader(FICHEIRO_CSV))) {
            br.readLine();
            String linha;

            while ((linha = br.readLine()) != null) {
                String[] campos = parseLinhaCSV(linha);
                if (campos == null) continue;

                String processo = campos[1].toLowerCase();
                if (!matriz.containsKey(processo)) continue;

                String estadoAtual = mapearEstado(campos[3]);
                if (estadoAtual == null) continue;

                String estadoAnterior = ultimoEstado.get(processo);
                if (estadoAnterior != null) {
                    double prob = matriz
                        .getOrDefault(processo, Collections.emptyMap())
                        .getOrDefault(estadoAnterior, Collections.emptyMap())
                        .getOrDefault(estadoAtual, 0.0);
                    
                    boolean eAnomalia = (prob < LIMIAR_ANOMALIA);
                    
                    // Atualiza a janela deslizante deste processo
                    Deque<Boolean> janela = janelas.computeIfAbsent(processo, k -> new ArrayDeque<>());
                    janela.addLast(eAnomalia);
                    if (janela.size() > TAMANHO_JANELA) {
                        janela.removeFirst();
                    }

                    // Só avalia quando a janela estiver cheia
                    if (janela.size() == TAMANHO_JANELA) {
                        long falhas = janela.stream().filter(b -> b).count();
                        double missRate = (double) falhas / TAMANHO_JANELA;

                        if (missRate >= LIMIAR_MISS_RATE) {
                            System.out.printf("[ALERTA] %-18s | Miss-Rate: %.1f%% (%d/%d transições anómalas)%n",
                                processo, missRate * 100, falhas, TAMANHO_JANELA);
                            janela.clear(); // Reinicia a janela para evitar spam consecutivo
                        }
                    }
                }
            }
        }
    }

    private static String[] parseLinhaCSV(String linha) {
        if (linha.length() < 2) return null;
        String limpa = linha.substring(1, linha.length() - 1);
        String[] campos = limpa.split("\",\"");
        return (campos.length >= 4) ? campos : null;
    }

    private static String mapearEstado(String operacao) {
        if (operacao.contains("CreateFile")) return "OPEN";
        if (operacao.contains("ReadFile")) return "READ";
        if (operacao.contains("WriteFile")) return "WRITE";
        if (operacao.contains("CloseFile")) return "CLOSE";
        if (operacao.contains("TCP") || operacao.contains("UDP")) return "NETWORK";
        return null;
    }

}
