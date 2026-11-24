package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Locale;

enum DisasterText {
    NO_PERMISSION("You need permission to test disasters.", "Você precisa de permissão para testar desastres."),
    SUBTITLE("Test disasters near your current location.", "Teste desastres perto da sua posição atual."),
    DETAILS("%d-block radius  •  %d s warning  •  %d s storm", "Raio de %d blocos  •  Aviso de %d s  •  Nevasca de %d s"),
    START("Start Blizzard", "Iniciar Blizzard"),
    STOP("Stop Test", "Encerrar teste"),
    CLOSE("Close", "Fechar"),
    IDLE("Ready", "Disponível"),
    WARNING("Starts in %d s", "Começa em %d s"),
    ACTIVE("Active · %d s left", "Ativa · restam %d s"),
    UNAVAILABLE("Required Blizzard assets could not be loaded.", "Não foi possível carregar os recursos da Blizzard."),
    ALERT("Blizzard incoming in %d s. Find shelter.", "Blizzard em %d s. Procure abrigo."),
    ARRIVAL("The Blizzard is here. Get under cover or near a lit campfire.",
        "A Blizzard chegou. Procure um teto ou uma fogueira acesa."),
    COLD("You are getting cold. Find shelter.", "Você está ficando com frio. Procure abrigo."),
    ENDED("The Blizzard has passed.", "A Blizzard passou."),
    CANCELLED("The Blizzard test was ended.", "O teste da Blizzard foi encerrado.");

    private final String english;
    private final String portuguese;

    DisasterText(String english, String portuguese) {
        this.english = english;
        this.portuguese = portuguese;
    }

    String get(PlayerRef player, Object... values) {
        String language = player.getLanguage();
        boolean pt = language != null && Locale.forLanguageTag(language.replace('_', '-')).getLanguage().equals("pt");
        String text = pt ? portuguese : english;
        return values.length == 0 ? text : text.formatted(values);
    }
}
