package com.armada.platform.protocol.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DefaultProtocolProcessCommandRunner implements ProtocolProcessCommandRunner {

    private static final int OUTPUT_LIMIT = 2_000;

    @Override
    public ProcessCommandResult run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process startedProcess = process;
            CompletableFuture<String> stdout =
                    CompletableFuture.supplyAsync(() -> readLimited(startedProcess.getInputStream()));
            CompletableFuture<String> stderr =
                    CompletableFuture.supplyAsync(() -> readLimited(startedProcess.getErrorStream()));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessCommandResult(-1, outputNow(stdout), outputNow(stderr), true);
            }

            return new ProcessCommandResult(process.exitValue(), stdout.join(), stderr.join(), false);
        } catch (IOException ex) {
            return new ProcessCommandResult(-1, "", ex.getMessage(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ProcessCommandResult(-1, "", "interrupted", true);
        }
    }

    private static String readLimited(InputStream inputStream) {
        try (inputStream) {
            byte[] bytes = inputStream.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() <= OUTPUT_LIMIT ? text : text.substring(0, OUTPUT_LIMIT);
        } catch (IOException ex) {
            return ex.getMessage();
        }
    }

    private static String outputNow(CompletableFuture<String> output) {
        return output.getNow("");
    }
}
