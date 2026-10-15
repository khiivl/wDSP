wDSP — 1.1 "Bulldozer" (compilación 26)
Cambios desde 0.4.9.8, la distribución anterior del 13.09.2026
==============================================================

Este lanzamiento es mi modificación de wDSP de Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, y contiene todo su trabajo hasta su versión 1.0.2.

INSTALACIÓN

- Sobre una versión anterior del mod: instale el APK encima; los ajustes y preajustes se conservan.
- Sobre la versión del autor: ambas están firmadas con claves distintas y Android no instalará
  una sobre la otra. Guarde sus preajustes con la copia de seguridad de la app, desinstale la versión
  del autor y luego instale el mod.
- Abra wDSP una vez tras la instalación.

Los manuales (nueve idiomas, incluido el mapa de gestos del salvapantallas) están en el archivo.
Se abren en cualquier navegador sin internet.

-----------------------------------------------------------------------------
NOVEDADES
-----------------------------------------------------------------------------

DE LA APP DEL AUTOR (khiivl, hasta su 1.0.2) — todo esto está también en el mod
- Ultra Bass, el nuevo Loudness, una curva de analizador en vivo detrás del ecualizador, "Apag."
  en la lista de subwoofer para coches sin él, GALA desactivable por preajuste.
- La pestaña "Prueba Audio": señales de prueba y grabaciones de prueba para cada altavoz.

SOLO EN EL MOD — la app del autor no hace esto
- Mide el coche: barridos por cada altavoz, grabados con el micrófono del equipo, y con lo que
  oye construye un preajuste, los retardos y una lista de problemas.
- Trabaja con el micrófono: el espectro y el RTA escuchan el habitáculo, también con la radio
  FM analógica, que no pasa por Android y sin micrófono no tiene espectro.
- El analizador es código nativo (C++), no Java.
- Un visualizador en la barra de estado y un salvapantallas a pantalla completa con control del
  reproductor.
- Cuida el sonido más allá del ecualizador: llamadas, el volumen de cada fuente, reanudar el
  reproductor tras reiniciar o suspender.
- Funciona por eventos, no por reloj: el servicio del autor comprueba el volumen y el
  reproductor cada 200 ms; el mod solo reacciona cuando algo cambia de verdad.
- Dos interfaces: Clásico (el aspecto del autor) y el Moderno propio del mod.

Novedades del mod en 1.1:

Interfaz
- Dos estilos de interfaz en Ajustes → "Estilo de interfaz": Clásico (el aspecto del autor) y
  Moderno. Temas diurno y nocturno en ambos.
- Bloqueo de pares para retardos: "Delanteros juntos" y "Traseros juntos" mantienen la diferencia
  entre izquierda y derecha.

Medición del habitáculo
- Ajustes → tarjeta del habitáculo: indique el tipo de habitáculo, sus medidas y toque los altavoces
  instalados.
- La aplicación elige el volumen de medición por sí misma.
- Tras "Apply Auto-EQ", un breve barrido de control muestra si la corrección ha funcionado.
- El informe comienza con una lista de problemas en palabras sencillas — por ejemplo, un altavoz débil,
  un tweeter con la polaridad invertida, canales intercambiados, un subwoofer sonando contra las puertas
  (con la indicación de cambiar su conmutador de fase). Los problemas que un ecualizador no puede
  solucionar se informan en lugar de "corregirse".

Espectro
- Un conmutador en la pantalla principal: "Desactivado", "Música" (lo que el preajuste hace con la música),
  "Microphone" (cómo suena realmente el habitáculo).
- "Cero RTA" en Prueba Audio calibra el espectro con una sola pulsación.
- Aviso cuando el micrófono está saturado: reduzca su sensibilidad en los ajustes del coche.

Reanudación del reproductor
- Ajustes: "Reanudar reproductor tras reiniciar" y "Reanudar reproductor tras suspensión". El reproductor
  que estaba sonando vuelve por sí solo, sin que su ventana salte sobre el navegador.
- Para la suspensión, pulse "Permitir wDSP activo en suspensión" y active wDSP en la lista que se abre.

Salvapantallas
- Deslice desde el centro de la pantalla para cambiar el volumen.
- Mantenga pulsado el botón de estilo un segundo para abrir el ecualizador.
- Un toque en la carátula abre el reproductor o la radio que realmente está sonando.
- Interruptor "Cubrir la barra de estado".

-----------------------------------------------------------------------------
CORREGIDO
-----------------------------------------------------------------------------

Autor (khiivl)
- El nivel del subwoofer al encender: una condición de carrera en el arranque lo fijaba mal (su 1.0.1).

Mod · Arranque y carga del procesador
- Sin tormenta al arrancar: el servicio arranca por etapas (sistema de audio, micrófono, MCU,
  preajustes) y no busca una sesión de audio cuando no suena nada.
- Tras un reinicio, la búsqueda de sesión ya no gira cada 4–5 s.
- El volumen, el reproductor, el visualizador y el salvapantallas reaccionan a eventos en lugar
  de sus propios temporizadores; GALA mantiene un temporizador solo mientras funciona.
- Los datos sin cambios no se reenvían a la MCU; los ajustes se guardan en disco una vez por
  pausa, no en cada paso del deslizador.
- El espectro duerme mientras no se oye nada.
- Los ajustes llegan de forma fiable al procesador de sonido tras encender y tras una actualización.

Mod · Otros
- Llamadas: el preajuste ya no salta durante una llamada, el micrófono se libera de inmediato y el
  volumen tras la llamada es el correcto.
- El salvapantallas ahora aparece en equipos con "vídeo flotante" o "navegación flotante" activados,
  no cubre el navegador y funciona en pantallas verticales.
- La pantalla del ecualizador y los diálogos se adaptan a pantallas estrechas y verticales.
- El espectro del micrófono tras la suspensión y tras el uso del micrófono por el asistente de voz.
- Varios errores en la medición del habitáculo, especialmente en graves profundos y con el subwoofer.
- GALA ya no deja el volumen elevado tras reducir la velocidad.

-----------------------------------------------------------------------------
POR FAVOR ENVÍE
-----------------------------------------------------------------------------

La versión del autor no tiene medición del habitáculo, por lo que las mediciones van al grupo del mod.

1. Tras una medición del habitáculo: el archivo de medición ("Guardar archivo") y el informe del
   sistema (Diagnóstico → "Informe del sistema"), ambos en Download/wDSP, junto con unas palabras sobre
   cómo cambió el sonido y la escena sonora.
2. Si el salvapantallas nunca aparece: Diagnóstico → "Screen Topology" → guardar y enviar.
3. Si la reanudación del reproductor le funcionó y con qué reproductor.

Grupo del mod: https://t.me/kostyamat_dev/92
Proyecto original: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
AGRADECIMIENTOS
-----------------------------------------------------------------------------

A Volodymyr Chebanenko (khiivl), autor de wDSP, por la aplicación sobre la que se construye este
lanzamiento. A Mirek (Polonia) por probar el ecualizador del autor en hardware real. A los probadores
que envían mediciones e informes.
