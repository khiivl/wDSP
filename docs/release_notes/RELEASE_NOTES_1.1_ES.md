wDSP — 0.4.9.9 (compilación 26)
Novedades desde la versión 0.4.9.8 (compilación 25)
===================================================

Una actualización importante: un mes de desarrollo con la versión 1.0 del autor ya integrada.
A continuación se detallan las novedades y cómo utilizarlas. El manual completo para cada función se
incluye en el paquete: manual_es.html (también disponible en inglés, ucraniano y ruso). Puede abrirse
en cualquier navegador sin necesidad de conexión a internet.

INSTALACIÓN

Instale el APK sobre la versión anterior, sin necesidad de desinstalar nada antes. Sus preajustes y
configuraciones se mantendrán intactos. Tras la instalación, abra wDSP una vez para iniciar su
servicio en segundo plano.

-----------------------------------------------------------------------------
1. DOS ESTILOS VISUALES: CLÁSICO Y MODERNO
-----------------------------------------------------------------------------

Se ha integrado la versión 1.0 del autor original. En Ajustes wDSP → «Estilo de interfaz» puede elegir:

- Clásico (Original) — la interfaz original del autor basada en Material 3;
- Moderno — la interfaz de cristal translúcido ya conocida en mis versiones.

Ambos estilos son totalmente compatibles con los temas claro y oscuro. Novedades procedentes de la 1.0:

- El gráfico del ecualizador traza ahora la curva real del procesador de sonido (Q 2.2), no un esbozo aproximado.
- Ultra Bass — realce del subwoofer que aumenta progresivamente con la rueda de volumen.
- Compensación de sonoridad (Loudness) mediante filtro shelf de graves, además de Recorte de agudos (Trim Highs).
- Bloqueo por parejas en los retardos: los canales izquierdo y derecho se mueven conjuntamente e informan de ello al activarse.
- Opción «Apagado» en la lista de frecuencias del subwoofer para vehículos sin sub.
- GALA (volumen según la velocidad) se puede desactivar para un perfil individual: «Desactivar para este preajuste».
- Pestaña «Comprobación de Audio»: ruido rosa, generador de seno a cualquier frecuencia, barrido (sweep)
  y pistas de prueba para cada altavoz por separado. Resulta ideal para comprobar cada altavoz
  individualmente antes de la medición.

-----------------------------------------------------------------------------
2. MEDICIÓN DEL HABITÁCULO (AUTO-EQ) — COMPLETAMENTE RENOVADA
-----------------------------------------------------------------------------

Cómo utilizarla ahora:

1. Ajustes wDSP → ficha del habitáculo. Indique si se trata de un vehículo cerrado o de un espacio
   abierto (banco de pruebas, descapotable), introduzca las dimensiones y TOQUE LOS ALTAVOCES en el
   esquema del coche para marcar cuáles están realmente instalados. Solo se medirán los altavoces
   declarados.
2. Calibre el micrófono (una sola vez por dispositivo).
3. Inicie la medición. Ya no es necesario ajustar el volumen manualmente: la aplicación lo selecciona de
   forma automática y, al mismo tiempo, mide los decibelios reales que entrega cada paso del control de
   volumen en su equipo.
4. Pulse «Apply Auto-EQ». Inmediatamente después, el programa reproducirá un breve BARRIDO DE CONTROL
   a través del nuevo preajuste y comprobará si la corrección funciona como es debido.

Qué muestra el nuevo informe:

- En primer lugar aparece una LISTA DE DEFECTOS explicada con claridad: un altavoz con menor nivel que
  su simétrico, un tweeter en contrafase con su propio woofer de puerta, canales izquierdo y derecho
  invertidos, resonancia de altavoz o modo de cabina por debajo de 200 Hz, o una medición ahogada en
  ruido ambiental.
- Si en el punto de corte el subwoofer trabaja en contrafase con las puertas, la aplicación le indicará
  que cambie la posición del interruptor de fase en el amplificador del subwoofer (0/180).
- La corrección se expresa en dB reales y no en valores internos del chip.
- El ruido de la cabina ya no cancela la medición: simplemente reduce el nivel de confianza en las
  bandas de frecuencia afectadas.

-----------------------------------------------------------------------------
3. ESPECTRO (RTA)
-----------------------------------------------------------------------------

- Elija su formato preferido: curva RTA en tiempo real o barras clásicas.
- El botón «Cero RTA» en Comprobación de Audio calibra ambos ceros simultáneamente (el canal directo y el
  micrófono): reproduce automáticamente ruido rosa, realiza la escucha y ajusta el cero.
- En caso de saturación del micrófono, el espectro muestra: «Micrófono sobrecargado: se muestra con AGC,
  forma aproximada. Baje la sensibilidad del micrófono». Reduzca la sensibilidad del micrófono en los
  ajustes de la radio hasta que desaparezca el aviso.
- El analizador entra en reposo cuando no hay reproducción activa y se reactiva automáticamente al
  reanudarse el audio.
- La música transmitida por Bluetooth ahora se visualiza en el espectro incluso en unidades que carecen
  del módulo BitPerfect.

-----------------------------------------------------------------------------
4. NOVEDAD: REANUDACIÓN DEL REPRODUCTOR TRAS REINICIO O SUSPENSIÓN
-----------------------------------------------------------------------------

Dos nuevos selectores en Ajustes wDSP: «Reanudar reproductor tras reiniciar» y «Reanudar reproductor
tras suspensión». Si había música sonando al apagarse la unidad, wDSP reanuda el mismo reproductor al
encenderse, sin superponer su pantalla sobre el lanzador o el navegador GPS. La radio se restablece por
sí misma y la aplicación no interfiere en ella.

Para la suspensión, pulse «Permitir wDSP activo en suspensión» debajo del interruptor: se abrirá la
lista blanca del sistema de la radio, donde debe marcar wDSP.

Se trata de una función nueva. Coménteme qué reproductor utiliza y si se reanudó correctamente.

-----------------------------------------------------------------------------
5. LLAMADAS, SALVAPANTALLAS Y VOLUMEN
-----------------------------------------------------------------------------

- Durante una llamada, la aplicación libera de inmediato el micrófono y activa el perfil específico para
  llamadas; al colgar, se restauran los ajustes previos.
- El salvapantallas se cierra en el instante en que entra una llamada.
- El salvapantallas ahora funciona correctamente en radios donde está activado el «vídeo flotante» o la
  «navegación flotante» en los ajustes de fábrica (antes no se mostraba en esas condiciones).
- La app de navegación configurada en el sistema se añade automáticamente a las excepciones del
  salvapantallas para no tapar el mapa. Si desea ver el salvapantallas sobre el navegador, elimínelo de
  dicha lista.
- La fila inferior del ecualizador ya no recorta los botones del amplificador en pantallas estrechas o
  verticales: toda la barra se comprime armónicamente.
- El volumen de cada fuente se memoriza de forma independiente y sigue los cambios efectuados fuera de
  wDSP.
- GALA ya no deja un paso de volumen residual tras cada deceleración.

-----------------------------------------------------------------------------
IDIOMAS
-----------------------------------------------------------------------------

La interfaz está disponible en 30 idiomas y variantes regionales.

-----------------------------------------------------------------------------
PETICIÓN A LOS TESTERS
-----------------------------------------------------------------------------

1. AUTOPREAJUSTES. ¿Considera que el preajuste se ha generado correctamente? ¿La escena sonora es
   adecuada? Por favor, envíe el archivo comprimido de la medición («Guardar archivo» tras medir) y el
   informe del sistema (Diagnóstico → «Informe del sistema»). Ambos archivos se guardan en la carpeta
   Download/wDSP.

2. SI EL SALVAPANTALLAS NUNCA LLEGA A APARECER: deje la radio durante dos minutos en cualquier
   aplicación salvo wDSP. A continuación abra Ajustes wDSP → Diagnóstico → «Screen Topology» → guarde y
   envíe el archivo generado. Si el salvapantallas sí aparece, pulse varias veces sobre él (en los
   controles de pistas y en los bordes de la pantalla) antes de guardar: el informe registrará las
   coordenadas exactas de las pulsaciones y cómo las interpretó el sistema.

3. REANUDACIÓN DEL REPRODUCTOR — indique qué reproductor utiliza, si fue tras reinicio o suspensión, y
   si reanudó la música con éxito.

Envíen todos los datos al grupo: https://t.me/wDSPapp/79

Si algo no funciona bien, añada un comentario explicando con detalle lo ocurrido. Si todo va perfecto,
simplemente comparta qué tal suenan los preajustes en su vehículo.

Muchas gracias.
