# jobmatchai-backend
Spring Boot backend for JobMatchAI platform

## Local OpenAI configuration

When running from the terminal, VS Code `launch.json` environment variables are not used.

Create a local `.env` file in the repository root or in `backend/`:

```properties
OPENAI_API_KEY=sk-your-openai-api-key-here
OPENAI_MODEL=gpt-4.1
```

Then start the backend from the `backend` directory:

```powershell
.\mvnw.cmd spring-boot:run
```

You can also set the variable for the current PowerShell session instead:

```powershell
$env:OPENAI_API_KEY="sk-your-openai-api-key-here"
.\mvnw.cmd spring-boot:run
```
