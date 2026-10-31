"""Base class and interface definition for AI tools."""

from abc import ABC, abstractmethod
from typing import Any


class BaseTool(ABC):
    """Abstract base class for all AI tools.

    Subclasses define their tool schema for OpenRouter and the execution
    logic. Crucially, execution enforces tenant isolation by requiring
    tenant_id at runtime from the system context.
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique identifier name of the tool (e.g., 'search_catalog_menu')."""
        pass

    @property
    @abstractmethod
    def description(self) -> str:
        """Detailed description of what the tool does, used by the LLM."""
        pass

    @property
    @abstractmethod
    def parameters(self) -> dict[str, Any]:
        """JSON Schema dictionary describing the parameters expected from LLM."""
        pass

    def to_openrouter_schema(self) -> dict[str, Any]:
        """Convert tool definition to OpenRouter / OpenAI function format."""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }

    @abstractmethod
    async def execute(self, *, tenant_id: str, **kwargs: Any) -> Any:
        """Execute the tool logic securely within the tenant boundary."""
        pass
