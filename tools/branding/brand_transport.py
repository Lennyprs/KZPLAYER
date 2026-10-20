"""Transport Google borné. Aucune relance automatique d'une écriture CI."""
import json
import socket
import ssl
import time
import urllib.error
import urllib.request
from http.client import HTTPException

MAX_RESPONSE = 4 * 1024 * 1024
READ_TIMEOUT = 60
WRITE_TIMEOUT = 180

class BackendError(RuntimeError):
    pass

class BackendUncertain(BackendError):
    def __init__(self, message, retryable=False):
        super().__init__(message)
        self.retryable = retryable

class BackendClient:
    def __init__(self, url, secret, opener=None, sleep=None, report=None):
        self.url, self.secret = url, secret
        self.opener = opener or urllib.request.urlopen
        self.sleep = sleep or time.sleep
        self.report = report or print

    def safe(self, message):
        text = str(message).replace(self.secret, '[masque]') if self.secret else str(message)
        return text.replace('\r', ' ').replace('\n', ' ')[:400]

    def call(self, action, **kwargs):
        if action not in {'clientBackendVersion', 'brandCiQueueAll', 'brandCiFetch', 'brandCiComplete'}:
            raise BackendError('Action de compilation non prise en charge.')
        read_only = action == 'clientBackendVersion'
        timeout = READ_TIMEOUT if read_only else WRITE_TIMEOUT
        payload = dict(kwargs, action=action)
        if not read_only:
            payload['secret'] = self.secret
        request = urllib.request.Request(self.url, data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
        def uncertain(message, retryable=False):
            suffix = '' if read_only else ' Reponse incertaine : aucune relance automatique de cette operation. Verifie son etat dans le panel avant de la reprendre.'
            return BackendUncertain(message + suffix, retryable=retryable)
        try:
            with self.opener(request, timeout=timeout) as response:
                raw = response.read(MAX_RESPONSE + 1)
        except urllib.error.HTTPError as error:
            code = int(error.code)
            retryable = code in {429, 500, 502, 503, 504}
            message = 'Google a refuse ou interrompu la reponse (HTTP %s).' % code
            if code in {401, 403}:
                message += ' Verifie le deploiement Web accessible a GitHub sans connexion Google.'
            raise uncertain(message, retryable) from None
        except (TimeoutError, socket.timeout):
            raise uncertain('Google ne repond pas dans le delai de %s secondes.' % timeout, True) from None
        except (urllib.error.URLError, OSError, HTTPException) as error:
            reason = getattr(error, 'reason', error)
            certificate = isinstance(reason, ssl.SSLCertVerificationError)
            message = 'Verification du certificat Google refusee.' if certificate else 'Connexion a Google interrompue ou indisponible.'
            raise uncertain(message, not certificate) from None
        if len(raw) > MAX_RESPONSE:
            raise uncertain('Reponse Google trop volumineuse.')
        try:
            data = json.loads(raw.decode('utf-8-sig'))
        except (ValueError, UnicodeError):
            raise uncertain('Google ne renvoie pas de JSON. Verifie que GAS_URL vise le deploiement /exec actuel, et non une page de connexion ou l editeur Google.') from None
        if not isinstance(data, dict) or not isinstance(data.get('ok'), bool):
            raise uncertain('Format de reponse Google inattendu.')
        if data['ok'] is not True:
            raise BackendError(self.safe(data.get('error', 'Operation refusee par Google.')))
        return data

    def probe(self):
        # Seul ce diagnostic SANS ECRITURE peut etre relance automatiquement.
        for attempt in range(2):
            try:
                result = self.call('clientBackendVersion')
                version = result.get('backendVersion')
                if not isinstance(version, int) or isinstance(version, bool) or version < 90:
                    raise BackendError('Le backend appele ne confirme pas une version compatible. Verifie GAS_URL et deploie le backend actuel sur cette adresse.')
                self.report('Connexion Google : OK (backend %s). Ce diagnostic ne cree aucune compilation.' % version)
                return result
            except BackendUncertain as error:
                if attempt or not error.retryable:
                    raise
                self.report('Diagnostic Google sans ecriture indisponible ; un nouvel essai dans 3 secondes. Aucun lancement de compilation.')
                self.sleep(3)
        raise BackendError('Diagnostic Google indisponible.')

if __name__ == '__main__':
    from brand_config import load_config
    import sys
    try:
        url, secret = load_config(report=True)
        BackendClient(url, secret).probe()
    except BackendError as error:
        print('::error::' + str(error))
        sys.exit(1)
