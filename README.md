# Aural

Aplicativo Android de downloads de áudio e vídeo por yt-dlp. Interface em português com visual Material 3.

## Recursos

- Recebe URLs pelo botão Compartilhar e por links abertos no Android.
- Importa arquivos JSON: listas de URLs, objetos com url/urls, e JSON de playlists do yt-dlp com entries/webpage_url.
- Reconhece mídia individual e playlists. Em playlists, permite buscar, selecionar faixas e baixar a coleção.
- Exibe os formatos disponíveis para mídia individual: ID, resolução, FPS, codec de vídeo/áudio, bitrate, frequência, extensão e tamanho (quando a origem informar).
- Escolhe stream de áudio, stream de vídeo e áudio de acompanhamento. Playlists têm áudio original ou vídeo sem limite/limite opcional de resolução.
- Mantém os codecs de origem. A escolha de áudio usa yt-dlp com --audio-format best; vídeo usa mux via FFmpeg, sem recodificação. Não há limite de qualidade imposto pelo app.
- Download em serviço em primeiro plano, fila sequencial, progresso, cancelamento e histórico local.
- Publica áudio em Música/Aural e vídeo em Vídeos/Aural com MediaStore. Playlists têm subpasta.

## Build

Abra **Actions → Android APK → Run workflow** ou faça push em main. Baixe o artefato **Aural-debug-APK** da execução. O APK de debug é instalável; inclui arm64-v8a e armeabi-v7a e usa Android 10 ou superior.

O workflow instala JDK 17 e Gradle 8.11.1 e executa gradle --no-daemon :app:assembleDebug.

## Detalhes técnicos

- yt-dlp/FFmpeg: [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android).
- Alguns sites exigem login, região, formatos especiais ou DRM; o app não implementa login/cookies nem quebra DRM.
- Formatos, bitrate e tamanho são os metadados disponibilizados pelo extrator. Playlists podem ter formatos diferentes por faixa; toque no ícone de qualidade de uma faixa para analisar seus formatos individuais.
- A fonte pode retirar um formato entre a análise e o download; nesse caso o erro aparece na lista.
- Downloads usam armazenamento temporário privativo antes da cópia para a biblioteca do Android. Espaço livre deve comportar temporariamente as duas cópias.
- No Android 15+, serviços do tipo dataSync têm limite de tempo imposto pelo sistema para trabalhos muito longos.

Use apenas mídia que você tem direito de baixar e respeite os termos da fonte.

Licença do projeto: GPL-3.0-or-later; a biblioteca youtubedl-android é GPL-3.0.
