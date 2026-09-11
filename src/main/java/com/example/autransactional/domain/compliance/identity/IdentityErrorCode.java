package com.example.autransactional.domain.compliance.identity;

/**
 * Codigos que el frontend ya sabe traducir a mensajes. El contrato dice que si el backend
 * devuelve {code, message}, la libreria usa 'code' para elegir el texto y cae a 'message'
 * cuando el codigo le es desconocido. Respetarlos es lo que hace utiles los mensajes al usuario.
 */
public enum IdentityErrorCode {

    LIP_MOVEMENT_NOT_DETECTED("No detectamos movimiento de labios. Di el numero en voz alta mirando a la camara."),
    NUMBER_CHALLENGE_EXPIRED("El reto de voz vencio. Solicita uno nuevo."),
    NUMBER_CHALLENGE_ALREADY_USED("Ese reto de voz ya fue utilizado."),
    INVALID_SPOKEN_NUMBER("El numero que dijiste no coincide con el que mostramos."),
    AUDIO_TRANSCRIPTION_FAILED("No pudimos entender el audio. Intenta en un lugar con menos ruido."),
    LIVENESS_SESSION_EXPIRED("La sesion de prueba de vida vencio. Vuelve a empezar."),
    LIVENESS_FAILED("La prueba de vida no fue superada."),
    LIVENESS_NOT_ENABLED("El servicio de prueba de vida no esta habilitado."),
    SPOOFING_DETECTED("Detectamos un intento de suplantacion."),
    MULTIPLE_FACES("Hay mas de un rostro en la imagen."),
    FACE_DISTANCE_ERROR("Acercate o alejate de la camara."),
    DOCUMENT_UNREADABLE("No pudimos leer el documento. Repite la captura con mejor luz."),
    SESSION_NOT_FOUND("La sesion de verificacion no existe o expiro."),
    SESSION_MISMATCH("Las pruebas no pertenecen a la misma sesion de verificacion."),
    VERIFICATION_INCOMPLETE("Faltan pasos por completar antes de validar la identidad."),
    MAX_ATTEMPTS_REACHED("Alcanzaste el maximo de intentos permitidos."),
    UNAUTHORIZED("No autorizado."),
    SERVER_ERROR("Ocurrio un error procesando la verificacion.");

    private final String defaultMessage;

    IdentityErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
