import json
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WIKI_DIR = REPO_ROOT / ".harness" / "wiki"


class ApiDocsGenerationTest(unittest.TestCase):

    def test_generates_armada_marketing_task_api_docs(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            endpoints_json = tmp_path / "armada_endpoints.json"
            api_markdown = tmp_path / "接口协议.md"

            subprocess.run(
                [
                    "python3",
                    str(WIKI_DIR / "parse_endpoints.py"),
                    "--root",
                    str(REPO_ROOT),
                    "--output",
                    str(endpoints_json),
                ],
                check=True,
                cwd=REPO_ROOT,
            )

            endpoints = json.loads(endpoints_json.read_text(encoding="utf-8"))
            task_controller = next(
                item for item in endpoints if item["controller"] == "MarketingTaskController"
            )
            paths = {f"{ep['method']} {ep['path']}" for ep in task_controller["endpoints"]}
            self.assertIn("PUT /api/marketing-tasks/{id}/marketing-template", paths)
            self.assertIn("GET /api/marketing-tasks/account-tree", paths)

            subprocess.run(
                [
                    "python3",
                    str(WIKI_DIR / "format_api.py"),
                    "--input",
                    str(endpoints_json),
                    "--output",
                    str(api_markdown),
                ],
                check=True,
                cwd=REPO_ROOT,
            )

            doc = api_markdown.read_text(encoding="utf-8")
            self.assertIn("## 营销任务 API（MarketingTaskController）", doc)
            self.assertIn("### PUT /api/marketing-tasks/{id}/marketing-template", doc)
            self.assertIn("通过任务修改其引用的营销模板。", doc)


if __name__ == "__main__":
    unittest.main()
