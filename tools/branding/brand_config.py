"""Contrôle explicite et sans affichage de secret. Standard library only."""
import os
import re
import sys
from urllib.parse import urlsplit, urlunsplit

class ConfigurationError(RuntimeError):
    pass

def load_config(environ=None, report=False):
    env = os.environ if environ is None else environ
    errors = []
    raw = str(env.get('GAS_URL', '')).strip()
    fallback = False
    if not raw:
        raw = str(env.get('GAS_URL_SECRET_FALLBACK', '')).strip()
        fallback = bool(raw)
    # Les guillemets ajoutés autour d'une valeur copiée ne font pas partie de l'URL.
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in ('"', "'"):
        raw = raw[1:-1].strip()
    gas = ''
    if not raw:
        errors.append('GAS_URL ABSENTE du job. Enregistre GAS_URL dans Settings > Secrets and variables > Actions > Variables > Repository variables. Une variable dans un Environment ne suffit pas pour ce workflow.')
    else:
        try:
            u = urlsplit(raw)
            path = u.path.rstrip('/')
            valid = (u.scheme == 'https' and u.netloc == 'script.google.com'
                     and re.fullmatch(r'/macros/s/[A-Za-z0-9_-]+/exec', path))
        except ValueError:
            valid = False
        if not valid:
            errors.append('GAS_URL INVALIDE : attendu URL HTTPS script.google.com/macros/s/IDENTIFIANT/exec. Ne pas utiliser le lien de l editeur, /dev ou seulement l identifiant du deploiement. Valeur non affichee.')
        else:
            gas = urlunsplit(('https', 'script.google.com', path, '', ''))
    secret = str(env.get('BRAND_CI_SECRET', '')).strip()
    if not secret:
        errors.append('BRAND_CI_SECRET ABSENT du job. Enregistre le secret dans Actions > Secrets > Repository secrets sous le nom exact BRAND_CI_SECRET. Ne pas le mettre seulement dans Variables ou dans un Environment.')
    elif len(secret) < 32:
        errors.append('BRAND_CI_SECRET TROP COURT : copie la VALEUR generee dans les proprietes Google, pas le nom de la propriete. Le secret n est pas affiche.')
    if report:
        print('GAS_URL : ' + ('OK (secret de compatibilite)' if fallback and gas else 'OK (variable de depot)' if gas else 'NON VALIDE'))
        print('BRAND_CI_SECRET : ' + ('PRESENT, format minimum OK (valeur non affichee)' if secret and len(secret) >= 32 else 'ABSENT OU TROP COURT (valeur non affichee)'))
        for error in errors:
            print('::error::' + error)
    if errors:
        raise ConfigurationError('\n'.join(errors))
    return gas, secret

if __name__ == '__main__':
    try:
        load_config(report=True)
    except ConfigurationError:
        sys.exit(1)
    print('Configuration recue par le job : OK. Sa validite aupres de Google sera verifiee lors de l appel suivant.')
