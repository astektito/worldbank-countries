# Countries Service — World Bank import microservice

Microservicio REST que importa países desde la [World Bank Countries API v2](https://api.worldbank.org/v2/country/CO?format=json), los persiste y los expone a través de su propia API.

## Cómo correrlo

```bash
./mvnw spring-boot:run
```

Levanta en `http://localhost:8080` con una base H2 en memoria (se crea al arrancar y muere con la app, no hace falta instalar nada).

Requiere **JDK 17+**. No hace falta tener Maven instalado: el wrapper (`./mvnw`) se encarga.

```bash
./mvnw clean verify      # compila y corre toda la suite de tests
./mvnw -B test           # solo los tests
```

Ningún test pega a la red real: hay dobles de prueba escritos a mano y, como segunda capa, `src/test/resources/application.yaml` apunta la URL externa al puerto discard (`http://localhost:9`), así que un descuido falla en milisegundos en lugar de colgarse.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/countries/import?code={code}` | Importa o actualiza un país desde la API externa |
| `GET` | `/api/countries` | Lista todos los países importados |
| `GET` | `/api/countries/{id}` | Devuelve un país por su id numérico |

Documentación interactiva en `http://localhost:8080/swagger-ui.html` y consola H2 en `http://localhost:8080/h2-console` (la URL JDBC exacta sale en el log de arranque).

### `POST /api/countries/import?code={code}`

`code` es un ISO 3166-1 alpha-2 o alpha-3 (2 o 3 letras). Devuelve **201** cuando crea el país y **200** cuando actualiza uno que ya existía: la operación es idempotente.

```bash
$ curl -i -X POST "http://localhost:8080/api/countries/import?code=CO"
HTTP/1.1 201
{"id":1,"iso2Code":"CO","iso3Code":"COL","name":"Colombia","capitalCity":"Bogota",
 "region":"Latin America & Caribbean","incomeLevel":"Upper middle income",
 "latitude":4.60987,"longitude":-74.082}

$ curl -i -X POST "http://localhost:8080/api/countries/import?code=CO"
HTTP/1.1 200        # el mismo id, sin fila duplicada
```

Códigos de error:

| Caso | Status | Body |
|---|---|---|
| `code` ausente, vacío, o no alfabético (`?code=1`) | **400** | `{"detail":"code must be 2 or 3 alphabetic characters"}` |
| `code` bien formado pero el país no existe (`?code=ZZ`) | **404** | `{"detail":"Country ZZ not found"}` |
| El país choca con una fila existente (importación concurrente) | **409** | `{"detail":"Country CO could not be stored: it conflicts with an existing record"}` |
| API externa caída, con timeout, o con respuesta no interpretable | **502** | `{"detail":"World Bank API is unreachable"}` |

### `GET /api/countries`

**200** con un array JSON. Devuelve `[]` (no un 404) cuando todavía no se importó nada.

```bash
$ curl -s "http://localhost:8080/api/countries"
[{"id":1,"iso2Code":"CO", ...}, {"id":2,"iso2Code":"PE", ...}]
```

### `GET /api/countries/{id}`

**200** con el país, o **404** con `{"detail":"Country 9999 not found"}`.

## Decisiones técnicas

### Stack

**Spring Boot 3.5.16** (el enunciado pide 3.x), **Java 17**, **Maven** con wrapper, **H2 en memoria**, **Spring Data JPA**, **`RestClient`**.

`RestClient` y no `WebClient`: el caso de uso es una sola llamada bloqueante cuyo resultado se necesita antes de responder. `WebClient` arrastraría `spring-webflux` y un modelo reactivo para después bloquear igual.

Sin Lombok, sin Actuator, sin devtools. El proyecto tiene 6 dependencias y todo el código es explícito.

### El problema real: la API externa devuelve los errores con HTTP 200

Esto es lo que decide la mitad del diseño. Pedir un código inexistente **no** da un 404:

```bash
$ curl -i "https://api.worldbank.org/v2/country/ZZ?format=json"
HTTP/1.1 200 OK
[{"message":[{"id":"120","key":"Invalid value","value":"The provided parameter value is not valid"}]}]
```

Un cliente que solo mire el status code trata ese error como un éxito y después explota al mapear. Encima la respuesta exitosa es una **tupla heterogénea** — `[{metadatos}, [países]]` — donde el índice 0 es metadatos *o* el error, y el índice 1 simplemente no está cuando hay error.

Por eso el parseo vive en una clase propia, `WorldBankResponseParser`, que recibe un `String` y distingue tres formas del body:

1. índice 0 con `message` → el código no existe → `CountryNotFoundException` → **404**
2. falta el índice 1, no es un array, o el array está vacío → el contrato no se cumple → `ExternalApiException` → **502**
3. la forma esperada → `WorldBankCountry`

Otras tres trampas del payload real, todas cubiertas por tests:

- **`region` e `incomeLevel` son objetos anidados**, no strings: hay que leer `region.value`. Un `get("region").asText()` devuelve `""` sin avisar.
- **`region.value` viene con un espacio al final**: `"Latin America & Caribbean "`. Se normaliza con `trim()`.
- **Los agregados regionales mandan `""`, no `null`**, en `capitalCity`, `latitude` y `longitude`. Todo blanco se normaliza a `null` en un solo helper: `""` no es lo mismo que ausencia, y `0.0` no puede hacer de "sin coordenada" porque es una coordenada válida.

### Estructura y SOLID

```
controller/  → HTTP: recibe, valida, elige el status. Cero lógica.
service/     → la regla de negocio (el upsert transaccional). Cero HTTP, cero JSON.
client/      → el borde externo. JsonNode no sale de este paquete.
repository/  → persistencia
model/       → la entidad JPA
dto/         → lo que la API propia expone
exception/   → las excepciones de dominio y el único lugar que conoce códigos HTTP
config/      → configuración externalizada
```

Hay exactamente **dos abstracciones extra**, y las dos se pagan solas:

- **`CountryProvider`** — puerto de un método hacia el mundo externo. `WorldBankCountryClient` hace solo HTTP; `WorldBankResponseParser` es una clase pura sin HTTP ni Spring en su firma. Se testea el parseo con JUnit puro y sin levantar contexto.
- **`CountryStore`** — puerto de cuatro métodos hacia la persistencia. El service depende de él y no de `JpaRepository`, así que en los tests se le escribe un fake a mano; contra `JpaRepository` habría que implementar unos 40 métodos que nadie usa.

Es Dependency Inversion (el service depende de interfaces que él define, no de infraestructura) más Interface Segregation (ninguna de las dos declara un método que su consumidor no llame). No están ahí por simetría: sin ellas, los tests exigirían un framework de mocking.

Inyección siempre por constructor con campos `private final`. Nunca `@Autowired` en campos: un objeto que sale del constructor a medio armar es un objeto que puede fallar más tarde y más lejos.

### Manejo de errores

Las excepciones de dominio (`CountryNotFoundException`, `ExternalApiException`) **no saben nada de HTTP**: sin `@ResponseStatus`, sin `ResponseEntity`. El mapeo a status vive en un único `@RestControllerAdvice`, así que el catálogo de errores de la API se lee de un archivo y el dominio se puede reusar desde algo que no sea REST.

El body de error es `record ErrorResponse(String detail)`. Un solo campo, y de tipo `String` a propósito: con Bean Validation es fácil terminar devolviendo un array de violaciones y romper a los clientes sin darse cuenta. El tipo lo impide por construcción.

Para que "todos los errores tienen esta forma" sea verdad y no una intención, hay un handler de `Exception` como red final: sin él, una excepción no prevista, un 405 o una ruta inexistente caen en el `BasicErrorController` de Boot y devuelven `{"timestamp","status","error","path"}`, una forma distinta y sin `detail`. Ese handler devuelve un mensaje genérico **fijo**, nunca `e.getMessage()`, para no filtrar detalles internos al cliente; el stacktrace va al log.

Un conflicto en la base es **409**, no 502: la API externa respondió bien, el problema es nuestro. Mapearlo a 502 culparía al proveedor de un problema local, y es el tipo de error que después se persigue en el lugar equivocado.

Los mensajes de validación están escritos a mano en inglés. No es cosmética: la máquina corre en locale `es_EC`, y los mensajes por defecto de Bean Validation saldrían en español según dónde se despliegue.

### Persistencia e idempotencia

PK subrogada `Long id` autogenerada, más una **unique constraint nombrada** (`uk_countries_iso2_code`) sobre `iso2_code`.

**Supuesto documentado**: el enunciado pide `GET /api/countries/{id}` sin aclarar si `{id}` es la PK numérica o el código ISO. Se resolvió como PK numérica, y esa es la lectura que hace que el criterio "restricción de unicidad" del enunciado tenga sentido — si `iso2` fuera la PK, la unicidad vendría gratis y no habría nada que restringir. La búsqueda por código alpha-2 es la extensión natural del servicio, y sería `GET /api/countries/by-code/{code}`.

La reimportación es un upsert `@Transactional`: se busca por `iso2Code` (case-insensitive, porque la API acepta `co` y `CO`), y se actualiza la fila o se crea una nueva. El código se normaliza siempre a mayúsculas con `Locale.ROOT` antes de guardarlo, así que importar `co` y después `CO` toca una sola fila. La unique constraint queda como red de seguridad estructural para dos importaciones concurrentes: la segunda falla en la base, no en una carrera del service.

### Configuración externalizada

La URL y los timeouts de la API externa viven en `application.yaml` y se bindean a un `record WorldBankProperties` con `@ConfigurationProperties`:

```yaml
worldbank:
  base-url: https://api.worldbank.org/v2
  connect-timeout: 2s
  read-timeout: 5s
```

Ninguna clase de negocio conoce la URL ni los timeouts. Se puede comprobar en caliente, y de paso demostrar el 502 sin desconectar internet:

```bash
WORLDBANK_BASE-URL=http://localhost:9/v2 ./mvnw spring-boot:run
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8080/api/countries/import?code=CO"   # 502
```

Los timeouts son obligatorios, no un detalle: sin `read-timeout` una API externa que acepta la conexión y no responde nunca deja hilos del servidor colgados hasta agotar el pool.

### Tests

JUnit 5, AssertJ y MockMvc, todo dentro de `spring-boot-starter-test`. **Sin Mockito**: los dobles son fakes escritos a mano (`FakeCountryProvider`, `FakeCountryStore`), que es posible justamente por los dos puertos. Un fake de veinte líneas se lee como código normal y no se rompe cuando cambia la firma de un método que el test no usaba.

- `WorldBankResponseParserTest` — JUnit puro, con los payloads reales de la API en text blocks. Cubre el país válido, el error con HTTP 200, la lista vacía, el body no-JSON, el índice faltante y el agregado con campos en blanco.
- `CountryServiceTest` — sin Spring: `new CountryService(fakeStore, fakeProvider)`. Verifica que la primera importación crea y la segunda actualiza.
- `CountryControllerTest` — `@SpringBootTest` + MockMvc, con el provider reemplazado por un fake. Verifica los status y el `detail` exacto de cada error, incluido un caso con `Locale` `es-EC` para que el mensaje de validación no se traduzca.

## Limitaciones conocidas

**La transacción de `importCountry` envuelve la llamada HTTP saliente.** `@Transactional` toma una conexión del pool y la retiene durante los hasta 7 segundos de timeout (2s de conexión + 5s de lectura) que puede tardar la API externa. Con el pool por defecto de Hikari (10 conexiones), unas diez importaciones concurrentes contra un proveedor lento retendrían todas las conexiones y `GET /api/countries` — que no depende de nada externo — empezaría a fallar también. Una falla externa se convertiría en la caída de un endpoint local sano, que es justamente lo que los timeouts buscaban evitar.

Está identificado y no corregido a propósito: la solución es mover el `fetch` afuera de la transacción y dejar `@Transactional` sólo en el upsert, en un bean distinto (una llamada a un método propio no pasa por el proxy de Spring, así que dividirlo dentro de la misma clase no tendría efecto). Es un refactor de dos clases que no cambia ninguna respuesta observable, y se dejó afuera del alcance de este ejercicio para no tocar código ya verificado.

## Fuera de alcance

Autenticación, paginación en el listado, caché de respuestas externas y despliegue con Docker/PostgreSQL. Con H2 en memoria el proyecto arranca con un comando y sin configuración previa, que es lo que pide el enunciado.
