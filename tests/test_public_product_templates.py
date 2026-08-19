import ast
import unittest
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SITE_APP = PROJECT_ROOT / "nxr_site" / "app.py"
TEMPLATE_ROOT = PROJECT_ROOT / "nxr_site" / "templates"


def load_product_profile_functions():
    source = SITE_APP.read_text(encoding="utf-8")
    module = ast.parse(source)
    selected_nodes = []
    for node in module.body:
        if isinstance(node, ast.Assign):
            names = {target.id for target in node.targets if isinstance(target, ast.Name)}
            if names & {"DEFAULT_PRODUCT_TYPE", "PRODUCT_TYPE_LABELS"}:
                selected_nodes.append(node)
        elif isinstance(node, ast.FunctionDef) and node.name in {
            "normalize_product_type",
            "build_product_profile",
        }:
            selected_nodes.append(node)

    namespace = {}
    exec(compile(ast.Module(body=selected_nodes, type_ignores=[]), str(SITE_APP), "exec"), namespace)
    return namespace["normalize_product_type"], namespace["build_product_profile"]


class PublicProductTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        normalize_product_type, build_product_profile = load_product_profile_functions()
        cls.normalize_product_type = staticmethod(normalize_product_type)
        cls.build_product_profile = staticmethod(build_product_profile)
        cls.templates = Environment(
            loader=FileSystemLoader(TEMPLATE_ROOT),
            autoescape=select_autoescape(("html",)),
        )

    def make_card(self, product_type=None, vintage_classification=""):
        card = {
            "cert_id": "5703018202",
            "card_name": "Synthetic Card",
            "card_category": "trading_card",
            "card_category_label": "Trading Card",
            "product_type": product_type,
            "vintage_classification": vintage_classification,
            "year": "1999",
            "brand": "Synthetic Brand",
            "set_name": "Synthetic Set",
            "card_number": "001",
            "language_label": "English",
            "pop": "2",
            "grade": "9.5",
            "final_grade_text": "9.5",
            "centering": 9.5,
            "edges": 9.0,
            "corners": 9.0,
            "surface": 9.5,
        }
        card["product_profile"] = self.build_product_profile(card)
        return card

    def render_components(self, card):
        names = (
            "components/card_identity.html",
            "components/card_grade.html",
            "components/card_facts.html",
            "components/card_subgrades.html",
            "components/card_verification.html",
        )
        return "\n".join(self.templates.get_template(name).render(card=card) for name in names)

    def test_missing_product_type_keeps_graded_card_contract(self):
        card = self.make_card()
        self.assertEqual(card["product_profile"]["product_type"], "graded_card")
        html = self.render_components(card)
        self.assertIn("Final Grade: 9.5", html)
        self.assertIn("Sub-Grades", html)
        self.assertIn("Authenticated and graded by NXR.", html)
        self.assertNotIn("product-type-mark", html)
        self.assertNotIn("verification-mark", html)

    def test_label_product_has_no_numeric_grade_or_subgrades(self):
        card = self.make_card("label_product")
        html = self.render_components(card)
        self.assertNotIn("Final Grade", html)
        self.assertNotIn("Sub-Grades", html)
        self.assertNotIn("Rarity", html)
        self.assertIn("Authenticated by NXR.", html)
        self.assertNotIn("graded", html.lower())
        self.assertNotIn("product-type-mark", html)
        self.assertNotIn("verification-mark", html)

    def test_vintage_product_highlights_year_and_classification_without_grade(self):
        card = self.make_card("vintage_product", "Archive Class II")
        html = self.render_components(card)
        self.assertIn("vintage-classification", html)
        self.assertIn("1999", html)
        self.assertIn("Archive Class II", html)
        self.assertNotIn("Final Grade", html)
        self.assertNotIn("Sub-Grades", html)
        self.assertIn("Authenticated and classified by NXR.", html)
        self.assertNotIn("product-type-mark", html)
        self.assertNotIn("verification-mark", html)

    def test_product_profiles_do_not_carry_visual_themes(self):
        graded_card = self.make_card()
        label_card = self.make_card("label_product")
        vintage_card = self.make_card("vintage_product")

        self.assertNotIn("theme", graded_card["product_profile"])
        self.assertNotIn("theme", label_card["product_profile"])
        self.assertNotIn("theme", vintage_card["product_profile"])
        self.assertEqual(label_card["product_profile"]["page_variant"], "graded-card")
        self.assertEqual(vintage_card["product_profile"]["page_variant"], "graded-card")

    def test_public_card_template_keeps_collector_components(self):
        source = (TEMPLATE_ROOT / "card.html").read_text(encoding="utf-8")
        self.assertIn('components/collector_ledger.html', source)
        self.assertIn('components/collector_timeline.html', source)
        self.assertNotIn('components/vintage_archive_index.html', source)
        self.assertIn("card-page--classic-card", source)

    def test_product_pages_use_default_shell_without_themes(self):
        source = (TEMPLATE_ROOT / "card.html").read_text(encoding="utf-8")
        media_source = (TEMPLATE_ROOT / "components" / "card_media.html").read_text(encoding="utf-8")

        self.assertNotIn('product-masthead', source)
        self.assertNotIn('card-products.css', source)
        self.assertIn('card-product-body--', source)
        self.assertNotIn('components/product_theme.html', source)
        self.assertIn('img-wrap--front', media_source)
        self.assertIn('img-wrap--back', media_source)
        self.assertIn('.card-page--classic-card .img-wrap--back img', source)

    def test_public_card_template_compiles_with_all_partials(self):
        self.templates.get_template("card.html")


if __name__ == "__main__":
    unittest.main()
