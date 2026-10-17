import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('configure_vercel_api', Path(__file__).resolve().parents[1] / 'configure_vercel_api.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VercelConfigurationTest(unittest.TestCase):
    def test_preserves_headers_and_other_routes_and_replaces_api_once(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'vercel.json'
            original = {'headers': [{'source': '/sw.js', 'headers': []}],
                        'rewrites': [{'source': '/help', 'destination': '/help.html'}]}
            destination.write_text(json.dumps(original), encoding='utf-8')
            MODULE.configure('https://api.example.com/', destination)
            MODULE.configure('https://new.example.com', destination)
            result = json.loads(destination.read_text(encoding='utf-8'))
            self.assertEqual(result['headers'], original['headers'])
            self.assertEqual(len(result['rewrites']), 2)
            self.assertEqual(result['rewrites'][0]['destination'], 'https://new.example.com/api/:path*')
            self.assertEqual(result['rewrites'][1], original['rewrites'][0])

    def test_rejects_local_origins_and_secrets_without_touching_file(self):
        for url in ['http://api.example.com', 'https://localhost', 'https://127.0.0.1',
                    'https://user:secret@api.example.com', 'https://api.example.com/api',
                    'https://api.example.com?key=secret', 'https://api.example.com/#token']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                MODULE.configure(url, Path('not-accessed.json'))
