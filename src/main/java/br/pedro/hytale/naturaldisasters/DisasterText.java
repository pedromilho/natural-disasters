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
    WARNING_SNOW("Light snow · %d s", "Neve leve · %d s"),
    WARNING_WIND("Wind rising · %d s", "Vento aumentando · %d s"),
    ACTIVE("Active · %d s left", "Ativa · restam %d s"),
    UNAVAILABLE("Required Blizzard assets could not be loaded.", "Não foi possível carregar os recursos da Blizzard."),
    ALERT("Blizzard incoming in %d s. Find shelter.", "Blizzard em %d s. Procure abrigo."),
    WIND_RISING("Wind is picking up. Blizzard incoming in %d s.", "O vento está aumentando. Blizzard em %d s."),
    ARRIVAL("The Blizzard is here. Get under cover or near a lit campfire.",
        "A Blizzard chegou. Procure um teto ou uma fogueira acesa."),
    COLD("You are getting cold. Find shelter.", "Você está ficando com frio. Procure abrigo."),
    ENDED("The Blizzard has passed.", "A Blizzard passou."),
    CANCELLED("The Blizzard test was ended.", "O teste da Blizzard foi encerrado."),
    QUAKE_DETAILS("Strong earthquakes open a large surface fissure.", "No nível Forte, o chão se abre em uma fenda grande."),
    QUAKE_LIGHT("Light", "Leve"),
    QUAKE_MODERATE("Moderate", "Moderado"),
    QUAKE_STRONG("Strong", "Forte"),
    QUAKE_WARNING("%s · starts in %d s", "%s · começa em %d s"),
    QUAKE_ACTIVE("%s · %d s left", "%s · restam %d s"),
    QUAKE_ALERT("Earthquake (%s) starts in %d s.", "Terremoto (%s) começa em %d s."),
    QUAKE_WARNING_TITLE("EARTHQUAKE", "TERREMOTO"),
    QUAKE_WARNING_MOVE("Heavy shaking incoming. Move away from cliffs.", "Tremor a caminho. Afaste-se de barrancos."),
    QUAKE_WARNING_SPLIT("Ground rupture imminent. Move away from the epicenter.", "O chão vai se abrir. Afaste-se do epicentro."),
    QUAKE_RUPTURE_TITLE("THE GROUND SPLIT", "O CHÃO SE ABRIU"),
    QUAKE_RUPTURE_DETAIL("Move away from the edge.", "Afaste-se da borda da fenda."),
    QUAKE_NO_SURFACE("A strong earthquake needs enough open natural ground to split.",
        "O Earthquake forte precisa de terreno natural aberto para formar uma fenda grande."),
    QUAKE_UNAVAILABLE("Required Earthquake assets could not be loaded.", "Não foi possível carregar os recursos do Earthquake."),
    QUAKE_FISSURE("A fissure opened near X %d, Z %d.", "Uma fenda abriu perto de X %d, Z %d."),
    QUAKE_ENDED("The earthquake has passed.", "O terremoto passou."),
    QUAKE_CANCELLED("The Earthquake test was ended.", "O teste do Earthquake foi encerrado.");

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
