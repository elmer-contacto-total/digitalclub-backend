# Holape - Spring Boot Backend

Migración del backend de DigitalGroup Web (Rails) a Spring Boot.

## Stack Tecnológico

- **Java**: 21 LTS
- **Spring Boot**: 3.2.5
- **Spring Security**: 6.2.x con JWT
- **Spring Data JPA**: 3.2.x
- **PostgreSQL**: Base de datos existente
- **Redis**: Cache y WebSocket
- **WebSocket STOMP**: Mensajes en tiempo real

## Requisitos

- Java 21+
- Maven 3.9+
- PostgreSQL (base de datos existente de Rails)
- Redis (para WebSocket y cache)

## Configuración

### Variables de Entorno

Crear un archivo `.env` o configurar las siguientes variables:

```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=digitalgroup_development
DB_USERNAME=postgres
DB_PASSWORD=postgres

# JWT
JWT_SECRET=your-256-bit-secret-key-here-minimum-32-characters

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# WhatsApp
WHATSAPP_WEBHOOK_VERIFY_TOKEN=your-verify-token

# Twilio (opcional)
TWILIO_ACCOUNT_SID=your-account-sid
TWILIO_AUTH_TOKEN=your-auth-token
TWILIO_PHONE_NUMBER=+1234567890

# AWS S3 (opcional)
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
AWS_REGION=us-east-1
AWS_S3_BUCKET=your-bucket
```

## Ejecución

### Desarrollo

```bash
# Compilar
mvn clean install

# Ejecutar
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# O con Java
java -jar target/holape-1.0.0.jar --spring.profiles.active=dev
```

### Producción

```bash
mvn clean package -Pprod
java -jar target/holape-1.0.0.jar --spring.profiles.active=prod
```

La aplicación estará disponible en: `http://localhost:8080`

## Endpoints Principales

### API Pública

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/api/v1/app_login` | Login para app móvil |
| GET/POST | `/whatsapp_webhook` | Webhook de WhatsApp |

### Admin (requiere JWT)

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/app/dashboard` | Dashboard con KPIs |
| GET | `/app/calculate_kpis` | Calcular KPIs |
| GET/POST | `/app/users` | CRUD de usuarios |
| GET/POST | `/app/messages` | Gestión de mensajes |
| GET/POST | `/app/tickets` | Gestión de tickets |
| POST | `/app/tickets/close` | Cerrar ticket |

### Documentación API

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

## Estructura del Proyecto

```
src/main/java/com/digitalgroup/holape/
├── HolapeApplication.java          # Clase principal
├── config/                         # Configuraciones
│   ├── SecurityConfig.java         # Spring Security + JWT
│   ├── WebSocketConfig.java        # STOMP WebSocket
│   └── ...
├── security/                       # Seguridad
│   ├── jwt/                        # JWT Provider y Filtros
│   ├── otp/                        # OTP Service (2FA)
│   └── CustomUserDetails.java
├── domain/                         # Dominio (DDD)
│   ├── user/                       # User, UserProfile, UserService
│   ├── client/                     # Client, ClientSetting
│   ├── message/                    # Message, MessageTemplate
│   ├── ticket/                     # Ticket, TicketService
│   ├── kpi/                        # Kpi, KpiService
│   ├── alert/                      # Alert
│   └── common/                     # Country, Language, Enums
├── api/                            # Controllers API REST
│   ├── v1/auth/                    # AuthController
│   └── webhook/                    # WhatsAppWebhookController
├── web/admin/                      # Controllers Admin
│   ├── DashboardController.java
│   ├── UserAdminController.java
│   ├── MessageAdminController.java
│   └── TicketAdminController.java
├── integration/                    # Integraciones externas
│   ├── whatsapp/                   # WhatsApp Cloud API
│   ├── sms/                        # Twilio, DigitalClub
│   └── firebase/                   # FCM
├── multitenancy/                   # Multi-tenant por client_id
└── exception/                      # Manejo de errores
```

## Autenticación

### JWT Token

El sistema usa JWT para autenticación. El token se obtiene via:

```bash
POST /api/v1/app_login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password",
  "phone": "51999999999"
}
```

Respuesta:
```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

Usar el token en requests:
```
Authorization: Bearer <access_token>
```

## WebSocket

Conectar a WebSocket para mensajes en tiempo real:

```javascript
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
    // Suscribirse a mensajes
    stompClient.subscribe('/user/queue/messages', function(message) {
        console.log(JSON.parse(message.body));
    });
});
```

## Migración desde Rails

Este proyecto reutiliza la base de datos PostgreSQL existente de Rails.

**IMPORTANTE**: `hibernate.ddl-auto=validate` - No se modifica el schema.

### Mapeo de Componentes

| Rails | Spring Boot |
|-------|-------------|
| Devise | Spring Security + JWT |
| CanCanCan | @PreAuthorize |
| Sidekiq Workers | @Async + @Scheduled |
| ActionCable | STOMP WebSocket |
| after_commit callbacks | @EventListener |
| ActiveRecord | Spring Data JPA |

## Tests

```bash
# Ejecutar tests
mvn test

# Con cobertura
mvn test jacoco:report
```

## Próximos Pasos

1. [ ] Completar controllers restantes (Imports, MessageTemplates, etc.)
2. [ ] Implementar jobs de background completos
3. [ ] Agregar tests de integración
4. [ ] Configurar CI/CD
5. [ ] Documentar APIs con OpenAPI
