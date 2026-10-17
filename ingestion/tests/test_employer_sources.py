import io
import json
import unittest
from unittest.mock import patch
from test_scraper import scraper


class EmployerSourcesTest(unittest.TestCase):
    def test_ifood_accepts_only_official_board_and_published_date(self):
        raw = {'title': 'Software Engineer Java', 'content': '&lt;p&gt;Java e SQL&lt;/p&gt;',
               'absolute_url': 'https://job-boards.greenhouse.io/ifoodcarreiras/jobs/123',
               'first_published': '2026-10-01', 'updated_at': '2026-10-15'}
        with patch.object(scraper, 'read_public_jobs', return_value=[raw, raw, {**raw, 'absolute_url': 'https://evil.example/job/1'}]):
            jobs = scraper.parse_ifood_jobs(scraper.IFOOD_API)
        self.assertEqual(len(jobs), 1)
        self.assertEqual(str(jobs[0]['published_date']), '2026-10-01')
        self.assertEqual(jobs[0]['company'], 'iFood')
        with self.assertRaises(ValueError): scraper.parse_ifood_jobs('http://localhost/private')

    def test_jsonld_graph_ignores_expired_and_breadcrumb_objects(self):
        job = {'@type': 'JobPosting', 'title': 'Backend Java', 'description': '<p>Java SQL</p>',
               'datePosted': '2026-10-01', 'hiringOrganization': {'name': 'Fixture'}}
        text = '<script type="application/ld+json">' + json.dumps({'@graph': [{'@type': 'BreadcrumbList'}, job]}) + '</script>'
        result = scraper.parse_career_posting(text, 'https://carreiras.itau.com.br/vaga/1', 'Itaú', 'Itaú')
        self.assertEqual(result['company'], 'Itaú')
        self.assertIn('Java', result['technologies'])
        job['validThrough'] = '2001-01-01'
        self.assertIsNone(scraper.parse_career_posting('<script type="application/ld+json">'+json.dumps(job)+'</script>', 'https://example.com/1', 'Empregare'))

    def test_sicoob_description_does_not_absorb_navigation(self):
        text = '<nav>Python</nav><h1>Suporte Java</h1><div class="vaga-descricao"><p>Java e SQL</p></div><footer>Docker</footer>'
        job = scraper.parse_career_posting(text, 'https://sicoob.empregare.com/pt-br/vaga-ti_1', 'Sicoob · Empregare', 'Sicoob')
        self.assertEqual(job['technologies'], ['Java', 'SQL'])
        self.assertIsNone(job['published_date'])
        self.assertIsNone(scraper.parse_career_posting(text+'Vaga encerrada', job['source_url'], job['source'], 'Sicoob'))

    def test_santander_filters_country_and_active_details(self):
        listing = {'total': 2, 'facets': [{'facetParameter': 'locationMainGroup', 'values': [
                       {'facetParameter': 'locationCountry', 'values': [{'descriptor': 'Brazil', 'id': 'BR'}]}]}],
                   'jobPostings': [{'externalPath': '/job/Brazil/Java_1'}, {'externalPath': '//evil.example/private'}]}
        detail = {'jobPostingInfo': {'title': 'Java Engineer', 'jobDescription': 'Java SQL Docker', 'country': {'id': 'BR'},
                  'canApply': True, 'posted': True, 'startDate': '2026-10-01',
                  'externalUrl': 'https://santander.wd3.myworkdayjobs.com/SantanderCareers/job/Brazil/Java_1'}}
        def read(url, body=None): return json.dumps(listing if body is not None else detail)
        with patch.object(scraper, 'read_career_resource', side_effect=read), patch.object(scraper.time, 'sleep'):
            jobs = scraper.parse_santander_jobs(scraper.SANTANDER_API)
            self.assertEqual(len(jobs), 1)
            detail['jobPostingInfo']['country']['id'] = 'US'
            self.assertEqual(scraper.parse_santander_jobs(scraper.SANTANDER_API), [])

    def test_employer_index_does_not_follow_external_or_unrelated_links(self):
        url = 'https://carreiras.itau.com.br/busca-de-vagas'
        page = '<a href="https://evil.example/vaga/1">x</a><a href="/auth/login">x</a><a href="/vaga/sp/java/35299/123">Java</a>'
        posting = '<script type="application/ld+json">'+json.dumps({'@type':'JobPosting','title':'Java','description':'Java SQL'})+'</script>'
        def read(link, body=None):
            return 'User-agent: *\nAllow: /' if link.endswith('/robots.txt') else page if link == url else posting
        with patch.object(scraper, 'read_career_resource', side_effect=read) as reader, patch.object(scraper.time, 'sleep'):
            self.assertEqual(len(scraper.parse_employer_pages(url)), 1)
            self.assertFalse(any('evil.example' in call.args[0] for call in reader.call_args_list))
        with self.assertRaises(ValueError): scraper.parse_employer_pages('http://localhost/private')
