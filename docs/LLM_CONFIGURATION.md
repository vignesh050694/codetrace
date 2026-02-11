# LLM Configuration for PR Analysis

This document explains how to configure LLM providers for PR analysis reports.

## Feature Flag

Control whether to use LLM-generated reports or static template reports:

**Environment Variable:**
```bash
USE_LLM_REPORTS=true  # Use LLM (default: false)
```

**In application.yml:**
```yaml
orkestify:
  report:
    use-llm: true  # or false for static reports
```

## Database Configuration

LLM settings are stored in the `llm_configuration` table. Each feature can use a different provider and model.

### Table Schema

```sql
CREATE TABLE llm_configuration (
    id VARCHAR(255) PRIMARY KEY,
    feature VARCHAR(100) NOT NULL UNIQUE,
    provider VARCHAR(50) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(500) NOT NULL,
    model VARCHAR(100) NOT NULL,
    max_tokens INTEGER,
    temperature DOUBLE PRECISION,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

### Configuration Examples

#### OpenAI (GPT-4)

```sql
INSERT INTO llm_configuration (id, feature, provider, base_url, api_key, model, max_tokens, temperature, active)
VALUES (
    'pr-analysis-openai',
    'PR_ANALYSIS',
    'OPENAI',
    'https://api.openai.com/v1',
    'sk-your-api-key-here',
    'gpt-4-turbo',
    4096,
    0.3,
    true
);
```

#### Anthropic Claude

```sql
INSERT INTO llm_configuration (id, feature, provider, base_url, api_key, model, max_tokens, temperature, active)
VALUES (
    'pr-analysis-claude',
    'PR_ANALYSIS',
    'ANTHROPIC',
    'https://api.anthropic.com/v1',
    'sk-ant-your-api-key-here',
    'claude-3-5-sonnet-20241022',
    4096,
    0.3,
    true
);
```

#### OpenRouter (Any Model)

```sql
INSERT INTO llm_configuration (id, feature, provider, base_url, api_key, model, max_tokens, temperature, active)
VALUES (
    'pr-analysis-openrouter',
    'PR_ANALYSIS',
    'OPENROUTER',
    'https://openrouter.ai/api/v1',
    'sk-or-your-api-key-here',
    'anthropic/claude-3.5-sonnet',
    4096,
    0.3,
    true
);
```

## Supported Providers

| Provider | API Format | Models |
|----------|------------|--------|
| **OPENAI** | OpenAI Chat Completions | gpt-4, gpt-4-turbo, gpt-3.5-turbo |
| **ANTHROPIC** | Anthropic Messages | claude-3-5-sonnet-20241022, claude-3-opus-20240229 |
| **OPENROUTER** | OpenAI-compatible | Any model on OpenRouter |

## Model Recommendations

### For PR Analysis

| Use Case | Recommended Model | Reasoning |
|----------|------------------|-----------|
| **Best Quality** | Claude 3.5 Sonnet | Most accurate risk assessment, best code understanding |
| **Cost-Effective** | GPT-4 Turbo | Good balance of quality and cost |
| **Fast & Cheap** | GPT-3.5 Turbo | Quick analysis, lower cost, good for high volume |

### Configuration Parameters

- **max_tokens**: 4096 recommended (full reports)
- **temperature**: 0.3 recommended (consistent, deterministic)
- **active**: Set to `false` to disable without deleting

## How It Works

### Static Reports (use-llm: false)

1. ImpactAnalysisService analyzes the changes
2. RiskAnalysisService calculates scores using predefined rules
3. ImpactReportFormatter formats as markdown
4. Posted to GitHub PR

### LLM Reports (use-llm: true)

1. ImpactAnalysisService analyzes the changes
2. LlmReportGenerator builds context from analysis data
3. LLM generates comprehensive report with:
   - Risk assessment and scores
   - Detailed impact analysis
   - Specific recommendations
   - WHO/WHAT/HOW for critical changes
4. Posted to GitHub PR

## System Prompt

The LLM is given a comprehensive system prompt that includes:

- Report structure requirements
- Scoring guidelines
- Style guidelines
- Critical rules for risk assessment

This ensures consistent, accurate reports across all PRs.

## Benefits of LLM Reports

✅ **More Accurate**: Nuanced understanding of code changes
✅ **Contextual**: Adapts explanations to specific changes
✅ **Natural Language**: Easier to read and understand
✅ **Flexible**: Can handle edge cases better
✅ **Comprehensive**: Provides detailed WHO/WHAT/HOW analysis

## Migration from Static to LLM

1. **Test with Static First**: Verify the system works
2. **Configure LLM**: Add database configuration
3. **Enable Flag**: Set `USE_LLM_REPORTS=true`
4. **Monitor Quality**: Review generated reports
5. **Adjust Prompt**: Fine-tune system prompt if needed

## Cost Estimation

Approximate costs per PR analysis (1000 PRs/month):

| Provider | Model | Input Tokens | Output Tokens | Cost/PR | Monthly Cost |
|----------|-------|--------------|---------------|---------|--------------|
| OpenAI | GPT-4 Turbo | ~2K | ~1K | $0.03 | $30 |
| OpenAI | GPT-3.5 Turbo | ~2K | ~1K | $0.002 | $2 |
| Anthropic | Claude 3.5 Sonnet | ~2K | ~1K | $0.015 | $15 |
| OpenRouter | Varies | ~2K | ~1K | $0.01-0.03 | $10-30 |

*Prices as of January 2025, may vary*

## Troubleshooting

### LLM Not Working

1. Check `USE_LLM_REPORTS` is set to `true`
2. Verify database configuration exists for `PR_ANALYSIS` feature
3. Check `active=true` in database
4. Verify API key is valid
5. Check logs for LLM API errors

### Poor Report Quality

1. Adjust `temperature` (lower = more consistent)
2. Increase `max_tokens` for longer reports
3. Try a different model
4. Review and adjust system prompt

### High Costs

1. Switch to cheaper model (GPT-3.5 Turbo)
2. Use OpenRouter with competitive pricing
3. Fall back to static reports for low-risk PRs
4. Implement caching for similar PRs

## Future Enhancements

- [ ] Different LLM models for different PR types
- [ ] Caching for similar changes
- [ ] A/B testing between static and LLM reports
- [ ] Custom prompts per repository
- [ ] Multi-model consensus for critical PRs
