package collector;
public class ExtratorProcMon {
    public static void main(String[] args) {
        // Nome exato do executável para a arquitetura do teu CPU
        String procmonExe = "collector/Procmon64.exe"; 
        String ficheiroBinario = "collector/recolha_real.pml";
        String ficheiroCSV = "collector/chamadas_sistema.csv";
        long tempoRecolhaMs = 60 * 1000; // 1 minuto de recolha
        
        try {
            System.out.println("[INFO] Passo 1: A iniciar o ProcMon no kernel de forma invisível...");
            // /AcceptEula (aceita os termos), /Quiet /Minimized (oculta a interface), /BackingFile (grava no disco)
            ProcessBuilder pbStart = new ProcessBuilder(procmonExe, "/AcceptEula", "/Quiet", "/Minimized", "/BackingFile", ficheiroBinario);
            pbStart.start();
            
            System.out.println("[INFO] Passo 2: A escutar system calls por 1 minuto.");
            System.out.println("-> DICA: Abre o Notepad agora, escreve um texto, guarda o ficheiro e fecha-o!");
            
            // O Java aguarda enquanto o ProcMon regista toda a atividade do processador
            Thread.sleep(tempoRecolhaMs);
            
            System.out.println("[INFO] Passo 3: A parar o ProcMon e fechar a escuta...");
            ProcessBuilder pbStop = new ProcessBuilder(procmonExe, "/Terminate");
            Process processoStop = pbStop.start();
            processoStop.waitFor(); // Espera que o ProcMon feche de forma limpa
            
            System.out.println("[INFO] Passo 4: A converter a telemetria do kernel para CSV (pode demorar uns segundos)...");
            // Abre o binário gerado e converte para um CSV amigável para o Python
            ProcessBuilder pbConvert = new ProcessBuilder(procmonExe, "/OpenLog", ficheiroBinario, "/SaveAs", ficheiroCSV);
            Process processoConvert = pbConvert.start();
            processoConvert.waitFor();
            
            System.out.println("[SUCESSO] Recolha terminada! O ficheiro '" + ficheiroCSV + "' está pronto para ser analisado.");
            
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao executar o ProcMon. Verificaste se o VS Code está a correr como Administrador?");
            e.printStackTrace();
        }
    }
}