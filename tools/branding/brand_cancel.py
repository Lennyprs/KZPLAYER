"""Arret cooperatif d'un seul lot, sans annuler les autres marques du workflow."""
import os
import signal
import subprocess
from brand_transport import BackendUncertain

class BuildCancelled(RuntimeError):
    pass

def ensure_job(call, job):
    data = call('brandCiJobStatus', job_id=job)
    if data.get('job_id') != job or not isinstance(data.get('cancelled'), bool):
        raise BackendUncertain('Etat de compilation non confirme. Publication interrompue par securite.')
    if data['cancelled']:
        raise BuildCancelled('Compilation annulee depuis le panel revendeur.')

def run_gradle(args, env, check, popen=None, killpg=None):
    check()
    process = (popen or subprocess.Popen)(args, env=env, start_new_session=True)
    def stop():
        if process.poll() is not None:
            return
        try:
            (killpg or os.killpg)(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                (killpg or os.killpg)(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
    try:
        while True:
            try:
                code = process.wait(timeout=20)
                if code:
                    raise subprocess.CalledProcessError(code, args)
                check()
                return
            except subprocess.TimeoutExpired:
                check()
    except BaseException:
        stop()
        raise
